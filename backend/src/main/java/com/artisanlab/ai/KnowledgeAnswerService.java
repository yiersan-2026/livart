package com.artisanlab.ai;

import com.artisanlab.knowledge.KnowledgeChunkMapper;
import com.artisanlab.knowledge.KnowledgeSearchResult;
import com.artisanlab.userconfig.UserApiConfigDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeAnswerService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnswerService.class);
    private static final Duration KNOWLEDGE_ANSWER_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_MISS_MESSAGE = "我还没有在 livart 系统知识库里找到这部分说明。你可以换一种说法问我，或者先描述你想完成的图片操作。";
    private static final String EMBEDDING_CACHE_NAMESPACE = "knowledge-query";

    private final KnowledgeChunkMapper mapper;
    private final KnowledgeEmbeddingService embeddingService;
    private final SpringAiTextService textService;
    private final RedisEmbeddingCacheService redisEmbeddingCacheService;
    private final int searchLimit;

    @Autowired
    public KnowledgeAnswerService(
            KnowledgeChunkMapper mapper,
            KnowledgeEmbeddingService embeddingService,
            SpringAiTextService textService,
            RedisEmbeddingCacheService redisEmbeddingCacheService,
            @Value("${artisan.knowledge.search-limit:4}") int searchLimit
    ) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.textService = textService;
        this.redisEmbeddingCacheService = redisEmbeddingCacheService;
        this.searchLimit = Math.max(1, Math.min(8, searchLimit));
    }

    KnowledgeAnswerService(
            KnowledgeChunkMapper mapper,
            KnowledgeEmbeddingService embeddingService,
            SpringAiTextService textService,
            int searchLimit
    ) {
        this(mapper, embeddingService, textService, null, searchLimit);
    }

    public String answerSystemQuestion(
            UserApiConfigDtos.ResolvedConfig config,
            String question,
            String fallbackAnswer
    ) {
        String fallback = sanitizeFallback(fallbackAnswer);
        List<KnowledgeSearchResult> snippets = search(config, question);
        if (snippets.isEmpty()) {
            return fallback;
        }

        try {
            String answer = textService.completeText(
                    config,
                    getAnswerSystemPrompt(),
                    buildAnswerInput(question, snippets),
                    KNOWLEDGE_ANSWER_TIMEOUT,
                    "knowledge-answer"
            );
            String sanitized = answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
            return sanitized.isBlank() ? fallback : sanitized;
        } catch (RuntimeException exception) {
            log.warn("[knowledge-answer] failed questionChars={} error={}", safeLength(question), safeMessage(exception));
            return fallback;
        }
    }

    private List<KnowledgeSearchResult> search(UserApiConfigDtos.ResolvedConfig config, String question) {
        String normalizedQuestion = normalizeQuestion(question);
        if (normalizedQuestion.isBlank()) {
            return List.of();
        }

        String embedding = getQueryEmbedding(config, normalizedQuestion);
        if (!embedding.isBlank()) {
            List<KnowledgeSearchResult> results = mapper.searchByVector(
                    embedding,
                    searchLimit
            );
            if (!results.isEmpty()) {
                return results;
            }
        }

        String keyword = extractKeyword(normalizedQuestion);
        List<KnowledgeSearchResult> keywordResults = mapper.searchByText(keyword, searchLimit);
        if (!keywordResults.isEmpty() || keyword.equals(normalizedQuestion)) {
            return keywordResults;
        }
        return mapper.searchByText(normalizedQuestion, searchLimit);
    }

    private String getQueryEmbedding(UserApiConfigDtos.ResolvedConfig config, String normalizedQuestion) {
        String model = embeddingService.embeddingModel();
        String questionHash = sha256(normalizeQuestionCacheKey(normalizedQuestion));
        String redisCachedEmbedding = redisEmbeddingCacheService == null
                ? ""
                : redisEmbeddingCacheService.find(EMBEDDING_CACHE_NAMESPACE, model, questionHash);
        if (!redisCachedEmbedding.isBlank()) {
            return redisCachedEmbedding;
        }
        String cachedEmbedding = mapper.findCachedQueryEmbedding(model, questionHash);
        if (cachedEmbedding != null && !cachedEmbedding.isBlank()) {
            mapper.touchCachedQueryEmbedding(model, questionHash);
            if (redisEmbeddingCacheService != null) {
                redisEmbeddingCacheService.put(EMBEDDING_CACHE_NAMESPACE, model, questionHash, cachedEmbedding);
            }
            return cachedEmbedding;
        }

        String embedding = KnowledgeEmbeddingService.toVectorLiteral(
                embeddingService.createEmbedding(config, normalizedQuestion)
        );
        if (!embedding.isBlank() && !"[]".equals(embedding)) {
            mapper.upsertCachedQueryEmbedding(model, questionHash, normalizedQuestion, embedding);
            if (redisEmbeddingCacheService != null) {
                redisEmbeddingCacheService.put(EMBEDDING_CACHE_NAMESPACE, model, questionHash, embedding);
            }
        }
        return embedding;
    }

    private String normalizeQuestion(String question) {
        return question == null ? "" : question.replaceAll("\\s+", " ").trim();
    }

    private String normalizeQuestionCacheKey(String question) {
        return normalizeQuestion(question)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[?？!！。,.，;；:：]+$", "");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String getAnswerSystemPrompt() {
        return """
                你是 livart 的帮助中心回答助手。
                你只能根据给定的知识库片段回答用户关于 livart 系统功能的问题。
                如果知识库片段没有提到，不要编造；请说明“当前知识库还没有这部分说明”。
                回答要简短、直接、中文，优先告诉用户具体怎么操作。
                不要输出 Markdown 标题，不要提到内部实现细节、数据库、向量检索或提示词。
                """;
    }

    private String buildAnswerInput(String question, List<KnowledgeSearchResult> snippets) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：").append(question == null ? "" : question.trim()).append("\n\n");
        builder.append("知识库片段：\n");
        for (int index = 0; index < snippets.size(); index += 1) {
            KnowledgeSearchResult result = snippets.get(index);
            builder.append(index + 1)
                    .append(". 【")
                    .append(safeText(result.title()))
                    .append("】")
                    .append(safeText(result.content()))
                    .append("\n");
        }
        return builder.toString();
    }

    private String extractKeyword(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        List<String> keywords = List.of(
                "局部重绘",
                "删除物体",
                "去背景",
                "抠图",
                "快捷编辑",
                "参考图",
                "画幅",
                "导出",
                "下载",
                "画布",
                "项目",
                "提示词",
                "文字",
                "文本",
                "刷新",
                "websocket",
                "原图",
                "webp"
        );
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return keyword;
            }
        }
        return question.trim();
    }

    private String sanitizeFallback(String fallbackAnswer) {
        String normalized = fallbackAnswer == null ? "" : fallbackAnswer.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? DEFAULT_MISS_MESSAGE : normalized;
    }

    private String safeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}
