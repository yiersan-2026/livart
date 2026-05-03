package com.artisanlab.ai;

import com.artisanlab.memory.UserMemoryEntity;
import com.artisanlab.memory.UserMemoryMapper;
import com.artisanlab.memory.UserMemorySearchResult;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Service
public class UserMemoryService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);
    private static final Duration USER_MEMORY_CAPTURE_TIMEOUT = Duration.ofSeconds(20);
    private static final String MEMORY_CONTEXT_PREFIX = "用户长期记忆（仅作个性化参考；若与本轮明确要求冲突，以本轮为准）：";
    private static final String EMBEDDING_CACHE_NAMESPACE = "user-memory-query";
    private static final Map<String, MemorySlotDefinition> SLOT_DEFINITIONS = Map.ofEntries(
            Map.entry("business_context", new MemorySlotDefinition("business_context", "业务背景", true, 1)),
            Map.entry("product_line", new MemorySlotDefinition("product_line", "主营产品", true, 2)),
            Map.entry("target_audience", new MemorySlotDefinition("target_audience", "目标人群", false, 3)),
            Map.entry("visual_style_preference", new MemorySlotDefinition("visual_style_preference", "视觉偏好", true, 4)),
            Map.entry("layout_preference", new MemorySlotDefinition("layout_preference", "版式偏好", true, 5)),
            Map.entry("copy_tone_preference", new MemorySlotDefinition("copy_tone_preference", "文案偏好", true, 6)),
            Map.entry("aspect_ratio_preference", new MemorySlotDefinition("aspect_ratio_preference", "画幅偏好", true, 7)),
            Map.entry("resolution_preference", new MemorySlotDefinition("resolution_preference", "清晰度偏好", true, 8)),
            Map.entry("platform_preference", new MemorySlotDefinition("platform_preference", "平台偏好", true, 9)),
            Map.entry("workflow_preference", new MemorySlotDefinition("workflow_preference", "工作流偏好", true, 10)),
            Map.entry("editing_constraint", new MemorySlotDefinition("editing_constraint", "编辑约束", true, 11)),
            Map.entry("quality_constraint", new MemorySlotDefinition("quality_constraint", "质量约束", true, 12))
    );

    private final ObjectMapper objectMapper;
    private final UserMemoryMapper mapper;
    private final KnowledgeEmbeddingService embeddingService;
    private final SpringAiTextService textService;
    private final RedisEmbeddingCacheService redisEmbeddingCacheService;
    private final UserApiConfigService userApiConfigService;
    private final int searchLimit;
    private final int recentLimit;
    private final double scoreThreshold;
    private final ExecutorService captureExecutor;

    @Autowired
    public UserMemoryService(
            ObjectMapper objectMapper,
            UserMemoryMapper mapper,
            KnowledgeEmbeddingService embeddingService,
            SpringAiTextService textService,
            RedisEmbeddingCacheService redisEmbeddingCacheService,
            UserApiConfigService userApiConfigService,
            @Value("${artisan.memory.search-limit:4}") int searchLimit,
            @Value("${artisan.memory.recent-limit:6}") int recentLimit,
            @Value("${artisan.memory.score-threshold:0.35}") double scoreThreshold,
            @Value("${artisan.memory.capture-worker-count:2}") int captureWorkerCount
    ) {
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.textService = textService;
        this.redisEmbeddingCacheService = redisEmbeddingCacheService;
        this.userApiConfigService = userApiConfigService;
        this.searchLimit = Math.max(1, Math.min(8, searchLimit));
        this.recentLimit = Math.max(1, Math.min(12, recentLimit));
        this.scoreThreshold = Math.max(0.0d, Math.min(1.0d, scoreThreshold));
        this.captureExecutor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(4, captureWorkerCount)),
                new MemoryThreadFactory()
        );
    }

    UserMemoryService(
            ObjectMapper objectMapper,
            UserMemoryMapper mapper,
            KnowledgeEmbeddingService embeddingService,
            SpringAiTextService textService,
            int searchLimit,
            int recentLimit,
            double scoreThreshold
    ) {
        this(objectMapper, mapper, embeddingService, textService, null, null, searchLimit, recentLimit, scoreThreshold);
    }

    UserMemoryService(
            ObjectMapper objectMapper,
            UserMemoryMapper mapper,
            KnowledgeEmbeddingService embeddingService,
            SpringAiTextService textService,
            RedisEmbeddingCacheService redisEmbeddingCacheService,
            UserApiConfigService userApiConfigService,
            int searchLimit,
            int recentLimit,
            double scoreThreshold
    ) {
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.textService = textService;
        this.redisEmbeddingCacheService = redisEmbeddingCacheService;
        this.userApiConfigService = userApiConfigService;
        this.searchLimit = Math.max(1, Math.min(8, searchLimit));
        this.recentLimit = Math.max(1, Math.min(12, recentLimit));
        this.scoreThreshold = Math.max(0.0d, Math.min(1.0d, scoreThreshold));
        this.captureExecutor = Executors.newSingleThreadExecutor(new MemoryThreadFactory());
    }

    public String buildRelevantMemoryContext(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            String latestUserMessage
    ) {
        if (userId == null) {
            return "";
        }
        try {
            Map<String, UserMemorySearchResult> selected = new LinkedHashMap<>();
            for (UserMemorySearchResult memory : mapper.listRecentMemories(userId, recentLimit)) {
                if (memory == null || !memory.sticky()) {
                    continue;
                }
                selected.putIfAbsent(memory.slotKey(), memory);
            }

            for (UserMemorySearchResult memory : searchRelevantMemories(userId, config, latestUserMessage)) {
                if (memory == null) {
                    continue;
                }
                if (memory.score() < scoreThreshold && !memory.sticky()) {
                    continue;
                }
                selected.putIfAbsent(memory.slotKey(), memory);
            }

            if (selected.isEmpty()) {
                return "";
            }

            List<UserMemorySearchResult> ordered = selected.values().stream()
                    .sorted(Comparator
                            .comparingInt((UserMemorySearchResult result) -> slotOrder(result.slotKey()))
                            .thenComparing(UserMemorySearchResult::sticky, Comparator.reverseOrder())
                            .thenComparing(Comparator.comparingInt(UserMemorySearchResult::importance).reversed()))
                    .toList();

            List<String> lines = new ArrayList<>();
            lines.add(MEMORY_CONTEXT_PREFIX);
            for (UserMemorySearchResult memory : ordered) {
                MemorySlotDefinition slot = SLOT_DEFINITIONS.get(memory.slotKey());
                String label = slot == null ? safeText(memory.title(), 60) : slot.label();
                String value = firstNonBlank(memory.summary(), memory.valueText());
                if (label.isBlank() || value.isBlank()) {
                    continue;
                }
                lines.add("- " + label + "：" + safeText(value, 220));
                mapper.touchMemory(userId, memory.slotKey());
            }
            return lines.size() <= 1 ? "" : String.join("\n", lines);
        } catch (Exception exception) {
            log.warn("[user-memory] build context failed userId={} error={}", userId, safeMessage(exception));
            return "";
        }
    }

    public void captureAgentTurnBestEffort(UUID userId, AgentMemoryCaptureRequest request) {
        if (userId == null || request == null || userApiConfigService == null) {
            return;
        }
        captureExecutor.execute(() -> {
            try {
                UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
                captureAgentTurn(userId, config, request);
            } catch (Exception exception) {
                log.warn("[user-memory] async capture failed userId={} error={}", userId, safeMessage(exception));
            }
        });
    }

    public void captureProductPosterAnalysisBestEffort(
            UUID userId,
            AiProxyDtos.ProductPosterAnalysisRequest request,
            AiProxyDtos.ProductPosterAnalysisResponse response
    ) {
        if (userId == null || request == null || response == null || userApiConfigService == null) {
            return;
        }
        captureExecutor.execute(() -> {
            try {
                UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
                captureProductPosterAnalysis(userId, config, request, response);
            } catch (Exception exception) {
                log.warn("[user-memory] async product capture failed userId={} error={}", userId, safeMessage(exception));
            }
        });
    }

    void captureAgentTurn(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            AgentMemoryCaptureRequest request
    ) {
        if (!shouldAttemptMemoryExtraction(request)) {
            return;
        }

        String responseText = textService.completeText(
                config,
                getMemoryCaptureSystemPrompt(),
                buildMemoryCaptureInput(request),
                USER_MEMORY_CAPTURE_TIMEOUT,
                "user-memory-capture"
        );
        List<MemoryCandidate> candidates = parseMemoryCandidates(responseText);
        upsertCandidates(userId, config, candidates, request.latestUserMessage());
    }

    void captureProductPosterAnalysis(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            AiProxyDtos.ProductPosterAnalysisRequest request,
            AiProxyDtos.ProductPosterAnalysisResponse response
    ) {
        List<MemoryCandidate> candidates = new ArrayList<>();
        addStructuredCandidate(candidates, "business_context", "业务背景",
                combineNonBlank(response.industry(), response.summary()), true, 88);
        addStructuredCandidate(candidates, "product_line", "主营产品",
                combineNonBlank(response.productName(), response.industry(), response.sellingPoints()), true, 92);
        addStructuredCandidate(candidates, "target_audience", "目标人群",
                response.targetAudience(), false, 72);
        addStructuredCandidate(candidates, "visual_style_preference", "视觉偏好",
                response.style(), true, 80);
        addStructuredCandidate(candidates, "layout_preference", "版式偏好",
                response.detailDesignStyle(), true, 86);
        addStructuredCandidate(candidates, "platform_preference", "平台偏好",
                response.platformStyle(), true, 70);
        addStructuredCandidate(candidates, "editing_constraint", "编辑约束",
                extractProductPosterConstraint(response), true, 74);
        upsertCandidates(userId, config, candidates, firstNonBlank(request.latestUserMessage(), request.description()));
    }

    @PreDestroy
    public void shutdown() {
        captureExecutor.shutdownNow();
    }

    private List<UserMemorySearchResult> searchRelevantMemories(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            String latestUserMessage
    ) {
        String normalizedQuery = normalizeQuery(latestUserMessage);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        try {
            String embedding = getQueryEmbedding(config, normalizedQuery);
            if (!embedding.isBlank() && !"[]".equals(embedding)) {
                List<UserMemorySearchResult> results = mapper.searchByVector(userId, embedding, searchLimit);
                if (!results.isEmpty()) {
                    return results;
                }
            }
        } catch (Exception exception) {
            log.warn("[user-memory] vector search failed userId={} error={}", userId, safeMessage(exception));
        }

        String keyword = extractKeyword(normalizedQuery);
        List<UserMemorySearchResult> keywordResults = mapper.searchByText(userId, keyword, searchLimit);
        if (!keywordResults.isEmpty() || keyword.equals(normalizedQuery)) {
            return keywordResults;
        }
        return mapper.searchByText(userId, normalizedQuery, searchLimit);
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

    private void upsertCandidates(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            List<MemoryCandidate> candidates,
            String sourceText
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (MemoryCandidate candidate : candidates) {
            MemorySlotDefinition slot = SLOT_DEFINITIONS.get(candidate.slotKey());
            if (slot == null || candidate.value().isBlank()) {
                continue;
            }
            UserMemoryEntity entity = new UserMemoryEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId(userId);
            entity.setSlotKey(slot.key());
            entity.setTitle(firstNonBlank(candidate.title(), slot.label()));
            entity.setSummary(firstNonBlank(candidate.summary(), candidate.value()));
            entity.setValueText(candidate.value());
            entity.setSourceText(safeText(sourceText, 1200));
            entity.setConfidence(normalizeConfidence(candidate.confidence()));
            entity.setImportance(Math.max(1, Math.min(100, candidate.importance())));
            entity.setSticky(candidate.sticky() || slot.sticky());
            entity.setContentHash(sha256(normalizeContentHashKey(slot.key(), candidate.value())));
            entity.setMetadataJson("{\"source\":\"user-memory\"}");
            String embeddingText = "";
            if (embeddingService.hasUsableConfig(config)) {
                embeddingText = KnowledgeEmbeddingService.toVectorLiteral(
                        embeddingService.createEmbedding(config, buildMemoryEmbeddingInput(entity))
                );
            }
            entity.setEmbeddingText(embeddingText);
            mapper.upsertMemory(entity);
            if (!embeddingText.isBlank() && !"[]".equals(embeddingText)) {
                mapper.updateVectorEmbedding(userId, slot.key(), embeddingText);
            }
        }
    }

    private List<MemoryCandidate> parseMemoryCandidates(String responseText) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObjectText(responseText));
            JsonNode memoriesNode = root.path("memories");
            if (!memoriesNode.isArray()) {
                return List.of();
            }
            List<MemoryCandidate> candidates = new ArrayList<>();
            for (JsonNode node : memoriesNode) {
                if (!node.isObject()) {
                    continue;
                }
                String slotKey = safeToken(node.path("slotKey").asText(""));
                String title = safeText(node.path("title").asText(""), 80);
                String summary = safeText(node.path("summary").asText(""), 280);
                String value = safeText(node.path("value").asText(""), 240);
                String confidence = normalizeConfidence(node.path("confidence").asText(""));
                int importance = node.path("importance").isInt() ? node.path("importance").asInt(70) : 70;
                boolean sticky = node.path("sticky").asBoolean(false);
                if (SLOT_DEFINITIONS.containsKey(slotKey) && !value.isBlank()) {
                    candidates.add(new MemoryCandidate(slotKey, title, summary, value, confidence, importance, sticky));
                }
            }
            return candidates;
        } catch (IOException exception) {
            log.warn("[user-memory] parse failed error={}", safeMessage(exception));
            return List.of();
        }
    }

    private String buildMemoryCaptureInput(AgentMemoryCaptureRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("最新用户输入：").append(safeText(request.latestUserMessage(), 2000)).append("\n");
        if (!safeText(request.conversationContext(), 4000).isBlank()) {
            builder.append("最近对话上下文：\n").append(safeText(request.conversationContext(), 4000)).append("\n");
        }
        if (request.aspectRatio() != null && !request.aspectRatio().isBlank()) {
            builder.append("当前画幅选择：").append(request.aspectRatio().trim()).append("\n");
        }
        if (request.imageResolution() != null && !request.imageResolution().isBlank()) {
            builder.append("当前清晰度选择：").append(request.imageResolution().trim()).append("\n");
        }
        if (request.requestedEditMode() != null && !request.requestedEditMode().isBlank()) {
            builder.append("当前编辑模式：").append(request.requestedEditMode().trim()).append("\n");
        }
        if (request.forcedToolId() != null && !request.forcedToolId().isBlank()) {
            builder.append("当前工具：").append(request.forcedToolId().trim()).append("\n");
        }
        return builder.toString().trim();
    }

    private String getMemoryCaptureSystemPrompt() {
        return """
                你是 livart 的“用户长期记忆提取器”。
                你的任务是：只从当前输入里提取“未来跨会话仍然有用”的稳定用户信息，用于后续个性化图片生成和图片编辑。

                只输出严格 JSON，不要 Markdown，不要解释，不要额外字段：
                {"memories":[{"slotKey":"固定枚举值","title":"短标题","summary":"对未来有用的一句话记忆","value":"最核心的记忆值","confidence":"high|medium|low","importance":1到100整数,"sticky":true或false}]}

                允许的 slotKey 只有：
                - business_context
                - product_line
                - target_audience
                - visual_style_preference
                - layout_preference
                - copy_tone_preference
                - aspect_ratio_preference
                - resolution_preference
                - platform_preference
                - workflow_preference
                - editing_constraint
                - quality_constraint

                提取规则：
                - 只保留稳定偏好、长期业务背景、持续产品方向、长期审美偏好、默认输出偏好、持续编辑约束。
                - 明显一次性的画面内容不要记，例如“这次生成一只小猫”“把鞋子放桌子上”。
                - 不要保存敏感个人信息，不要保存年龄、身高、体重、电话号码、密钥、密码、露骨性描述、一次性隐私细节。
                - 如果用户表达“以后默认”“我偏好”“我喜欢”“我卖/我做/我主营”“我的客户是”“整体风格想要”，通常值得记忆。
                - 如果当前输入没有长期价值，返回空数组。
                - summary 和 value 用中文，简洁自然，可直接给后续模型参考。
                - sticky=true 只用于真正应长期默认继承的偏好，例如默认画幅、默认清晰度、稳定视觉风格、长期业务背景。
                """;
    }

    private static boolean shouldAttemptMemoryExtraction(AgentMemoryCaptureRequest request) {
        if (request == null || request.latestUserMessage() == null) {
            return false;
        }
        String normalized = request.latestUserMessage().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.length() >= 18) {
            return true;
        }
        return normalized.contains("默认")
                || normalized.contains("以后")
                || normalized.contains("偏好")
                || normalized.contains("喜欢")
                || normalized.contains("我卖")
                || normalized.contains("我做")
                || normalized.contains("主营");
    }

    private static void addStructuredCandidate(
            List<MemoryCandidate> candidates,
            String slotKey,
            String title,
            String value,
            boolean sticky,
            int importance
    ) {
        String normalizedValue = safeText(value, 240);
        if (normalizedValue.isBlank() || "未填写".equals(normalizedValue) || "通用商业".equals(normalizedValue)) {
            return;
        }
        candidates.add(new MemoryCandidate(slotKey, title, normalizedValue, normalizedValue, "high", importance, sticky));
    }

    private static String extractProductPosterConstraint(AiProxyDtos.ProductPosterAnalysisResponse response) {
        String sellingPoints = safeText(response.sellingPoints(), 240);
        if (sellingPoints.isBlank()) {
            return "";
        }
        return "后续商品图优先围绕这些卖点表达：" + sellingPoints;
    }

    private static String buildMemoryEmbeddingInput(UserMemoryEntity entity) {
        return String.join("\n", List.of(
                firstNonBlank(entity.getTitle(), ""),
                firstNonBlank(entity.getSummary(), ""),
                firstNonBlank(entity.getValueText(), "")
        )).trim();
    }

    private static String normalizeQuery(String question) {
        return question == null ? "" : question.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeQuestionCacheKey(String question) {
        return normalizeQuery(question)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[?？!！。,.，;；:：]+$", "");
    }

    private static String normalizeContentHashKey(String slotKey, String value) {
        return slotKey + "::" + value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String extractKeyword(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        List<String> keywords = List.of(
                "贴纸",
                "香水",
                "内衣",
                "商品详情图",
                "详情图",
                "9:16",
                "16:9",
                "2k",
                "4k",
                "科技感",
                "简洁优雅",
                "高级留白",
                "小红书",
                "抖音",
                "淘宝",
                "平台"
        );
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return keyword;
            }
        }
        return question.trim();
    }

    private static String extractJsonObjectText(String rawText) {
        String normalized = rawText == null ? "" : rawText.trim();
        int firstBrace = normalized.indexOf('{');
        int lastBrace = normalized.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return normalized.substring(firstBrace, lastBrace + 1);
        }
        return normalized;
    }

    private static String safeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
    }

    private static String normalizeConfidence(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "low" -> normalized;
            default -> "medium";
        };
    }

    private static int slotOrder(String slotKey) {
        MemorySlotDefinition slot = SLOT_DEFINITIONS.get(slotKey);
        return slot == null ? 999 : slot.order();
    }

    private static String safeText(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private static String combineNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String normalized = safeText(value, 160);
                if (!normalized.isBlank() && !"未填写".equals(normalized)) {
                    parts.add(normalized);
                }
            }
        }
        return String.join("；", new LinkedHashSet<>(parts));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable == null ? "unknown" : throwable.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    record AgentMemoryCaptureRequest(
            String latestUserMessage,
            String conversationContext,
            String aspectRatio,
            String imageResolution,
            String requestedEditMode,
            String forcedToolId
    ) {
    }

    private record MemoryCandidate(
            String slotKey,
            String title,
            String summary,
            String value,
            String confidence,
            int importance,
            boolean sticky
    ) {
    }

    private record MemorySlotDefinition(
            String key,
            String label,
            boolean sticky,
            int order
    ) {
    }

    private static final class MemoryThreadFactory implements ThreadFactory {
        private int index = 0;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "livart-user-memory-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    }
}
