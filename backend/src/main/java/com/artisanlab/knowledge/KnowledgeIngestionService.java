package com.artisanlab.knowledge;

import com.artisanlab.ai.KnowledgeEmbeddingService;
import com.artisanlab.userconfig.UserApiConfigDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final KnowledgeChunkMapper mapper;
    private final KnowledgeEmbeddingService embeddingService;
    private final ResourcePatternResolver resourcePatternResolver;
    private final boolean autoIndexEnabled;
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultChatModel;
    private final int embeddingBatchSize;

    public KnowledgeIngestionService(
            KnowledgeChunkMapper mapper,
            KnowledgeEmbeddingService embeddingService,
            ResourcePatternResolver resourcePatternResolver,
            @Value("${artisan.knowledge.auto-index-enabled:true}") boolean autoIndexEnabled,
            @Value("${artisan.ai.default-base-url:}") String defaultBaseUrl,
            @Value("${artisan.ai.default-api-key:}") String defaultApiKey,
            @Value("${artisan.ai.default-chat-model:gpt-5.5}") String defaultChatModel,
            @Value("${artisan.knowledge.embedding-batch-size:24}") int embeddingBatchSize
    ) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.resourcePatternResolver = resourcePatternResolver;
        this.autoIndexEnabled = autoIndexEnabled;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultChatModel = defaultChatModel == null ? "gpt-5.5" : defaultChatModel.trim();
        this.embeddingBatchSize = Math.max(1, Math.min(100, embeddingBatchSize));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        if (!autoIndexEnabled) {
            return;
        }

        try {
            refreshSystemKnowledge();
            fillMissingEmbeddings();
        } catch (RuntimeException exception) {
            log.warn("[knowledge-index] startup indexing skipped error={}", safeMessage(exception));
        }
    }

    @Transactional
    public void refreshSystemKnowledge() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath*:knowledge/*.md");
            for (Resource resource : resources) {
                indexResource(resource);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("read knowledge resources failed", exception);
        }
    }

    public KnowledgeEmbeddingFillResult fillMissingEmbeddings() {
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                trimTrailingSlash(defaultBaseUrl),
                defaultApiKey,
                "",
                defaultChatModel,
                "",
                "",
                true
        );

        if (!embeddingService.hasUsableConfig(config)) {
            throw new IllegalStateException("知识库向量生成配置缺失：请配置 LIVART_KNOWLEDGE_EMBEDDING_API_KEY");
        }

        boolean vectorColumnAvailable = hasVectorColumn();
        Set<UUID> attemptedEmbeddingChunkIds = new HashSet<>();
        int embeddedChunks = 0;
        int vectorChunks = 0;
        int failedChunks = 0;

        while (true) {
            List<KnowledgeChunkEntity> chunks = mapper.listChunksMissingEmbedding(embeddingBatchSize);
            List<KnowledgeChunkEntity> newChunks = chunks.stream()
                    .filter(chunk -> chunk.getId() != null && !attemptedEmbeddingChunkIds.contains(chunk.getId()))
                    .toList();
            if (newChunks.isEmpty()) {
                break;
            }

            for (KnowledgeChunkEntity chunk : newChunks) {
                attemptedEmbeddingChunkIds.add(chunk.getId());
                try {
                    String vector = KnowledgeEmbeddingService.toVectorLiteral(
                            embeddingService.createEmbedding(config, chunk.getTitle() + "\n" + chunk.getContent())
                    );
                    if ("[]".equals(vector)) {
                        failedChunks += 1;
                        log.warn("[knowledge-index] embedding empty chunk={}", chunk.getId());
                        continue;
                    }
                    mapper.updateEmbeddingText(chunk.getId(), vector);
                    embeddedChunks += 1;
                    if (vectorColumnAvailable && updateVectorEmbedding(chunk.getId(), vector)) {
                        vectorChunks += 1;
                    }
                } catch (RuntimeException exception) {
                    failedChunks += 1;
                    log.warn("[knowledge-index] embedding failed chunk={} error={}", chunk.getId(), safeMessage(exception));
                }
            }
        }

        if (vectorColumnAvailable) {
            vectorChunks += backfillVectorEmbeddingsFromText();
        }

        log.info("[knowledge-index] embedding fill done embedded={} vectorBackfilled={} failed={}", embeddedChunks, vectorChunks, failedChunks);
        return new KnowledgeEmbeddingFillResult(embeddedChunks, vectorChunks, failedChunks);
    }

    private void indexResource(Resource resource) throws IOException {
        String sourcePath = resource.getFilename() == null ? "knowledge" : resource.getFilename();
        String slug = slugFromFilename(sourcePath);
        String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
        if (content.isBlank()) {
            return;
        }

        String contentHash = sha256(content);
        String existingHash = mapper.findDocContentHashBySlug(slug);
        if (contentHash.equals(existingHash)) {
            return;
        }

        UUID docId = deterministicUuid("knowledge-doc:" + slug);
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setId(docId);
        doc.setSlug(slug);
        doc.setTitle(extractTitle(content, slug));
        doc.setSourcePath(sourcePath);
        doc.setContentHash(contentHash);
        mapper.upsertDoc(doc);
        mapper.deleteChunksByDocId(docId);

        List<ChunkDraft> chunks = splitMarkdown(content);
        for (int index = 0; index < chunks.size(); index += 1) {
            ChunkDraft draft = chunks.get(index);
            KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
            chunk.setId(deterministicUuid("knowledge-chunk:" + slug + ":" + index + ":" + sha256(draft.content())));
            chunk.setDocId(docId);
            chunk.setDocSlug(slug);
            chunk.setChunkIndex(index);
            chunk.setTitle(draft.title());
            chunk.setContent(draft.content());
            chunk.setContentHash(sha256(draft.content()));
            chunk.setMetadataJson("{\"source\":\"system\"}");
            mapper.upsertChunk(chunk);
        }
        log.info("[knowledge-index] indexed slug={} chunks={}", slug, chunks.size());
    }

    private boolean hasVectorColumn() {
        try {
            return mapper.hasVectorColumn();
        } catch (RuntimeException exception) {
            log.info("[knowledge-index] pgvector column check skipped error={}", safeMessage(exception));
            return false;
        }
    }

    private boolean updateVectorEmbedding(UUID chunkId, String vector) {
        try {
            mapper.updateVectorEmbedding(chunkId, vector);
            return true;
        } catch (RuntimeException vectorException) {
            log.info("[knowledge-index] pgvector column update skipped chunk={} error={}", chunkId, safeMessage(vectorException));
            return false;
        }
    }

    private int backfillVectorEmbeddingsFromText() {
        int updated = 0;
        while (true) {
            int batchUpdated;
            try {
                batchUpdated = mapper.backfillVectorEmbeddingsFromText(embeddingBatchSize);
            } catch (RuntimeException exception) {
                log.info("[knowledge-index] pgvector text backfill skipped error={}", safeMessage(exception));
                break;
            }
            if (batchUpdated <= 0) {
                break;
            }
            updated += batchUpdated;
        }
        return updated;
    }

    private List<ChunkDraft> splitMarkdown(String content) {
        List<ChunkDraft> chunks = new ArrayList<>();
        String currentTitle = extractTitle(content, "livart");
        StringBuilder current = new StringBuilder();

        for (String line : content.split("\\R")) {
            if (line.startsWith("## ") && !current.isEmpty()) {
                addChunk(chunks, currentTitle, current.toString());
                current.setLength(0);
                currentTitle = line.replaceFirst("^##\\s+", "").trim();
            }
            current.append(line).append("\n");
        }
        addChunk(chunks, currentTitle, current.toString());
        return chunks;
    }

    private void addChunk(List<ChunkDraft> chunks, String title, String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return;
        }
        if (normalized.length() <= 1600) {
            chunks.add(new ChunkDraft(title, normalized));
            return;
        }

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + 1600);
            chunks.add(new ChunkDraft(title, normalized.substring(start, end).trim()));
            start = end;
        }
    }

    private String extractTitle(String content, String fallback) {
        for (String line : content.split("\\R")) {
            if (line.startsWith("# ")) {
                String title = line.replaceFirst("^#\\s+", "").trim();
                if (!title.isBlank()) {
                    return title;
                }
            }
        }
        return fallback;
    }

    private String slugFromFilename(String filename) {
        return filename.replaceAll("\\.md$", "").replaceAll("[^a-zA-Z0-9_-]+", "-").toLowerCase();
    }

    private UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private record ChunkDraft(String title, String content) {
    }

    public record KnowledgeEmbeddingFillResult(int embeddedChunks, int vectorChunks, int failedChunks) {
    }
}
