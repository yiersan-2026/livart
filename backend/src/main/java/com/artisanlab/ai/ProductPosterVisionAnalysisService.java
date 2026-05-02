package com.artisanlab.ai;

import com.artisanlab.asset.AssetService;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductPosterVisionAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ProductPosterVisionAnalysisService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Map<String, String> FACT_LABELS = Map.ofEntries(
            Map.entry("productName", "产品名称"),
            Map.entry("industry", "行业"),
            Map.entry("material", "材质 / 成分 / 原料"),
            Map.entry("size", "尺寸 / 规格 / 容量"),
            Map.entry("color", "颜色 / 外观"),
            Map.entry("style", "款式 / 风格"),
            Map.entry("scenarios", "适用场景"),
            Map.entry("targetAudience", "使用人群"),
            Map.entry("sellingPoints", "核心卖点"),
            Map.entry("extraDetails", "补充参数"),
            Map.entry("platformStyle", "平台风格")
    );

    private final ObjectMapper objectMapper;
    private final AssetService assetService;
    private final boolean enabled;
    private final Duration timeout;
    private final int maxImages;
    private final HttpClient httpClient;

    public ProductPosterVisionAnalysisService(
            ObjectMapper objectMapper,
            AssetService assetService,
            @Value("${artisan.ai.product-poster-vision-enabled:true}") boolean enabled,
            @Value("${artisan.ai.product-poster-vision-timeout-seconds:35}") int timeoutSeconds,
            @Value("${artisan.ai.product-poster-vision-max-images:4}") int maxImages
    ) {
        this.objectMapper = objectMapper;
        this.assetService = assetService;
        this.enabled = enabled;
        this.timeout = Duration.ofSeconds(Math.max(10, Math.min(90, timeoutSeconds)));
        this.maxImages = Math.max(1, Math.min(6, maxImages));
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public Optional<VisionAnalysisResult> analyze(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            List<AiProxyDtos.ImageReferenceCandidate> images,
            String userDescription
    ) {
        List<ImageInput> usableImages = normalizeImages(images);
        if (!enabled || config == null || usableImages.isEmpty() || isBlank(config.baseUrl()) || isBlank(config.apiKey())) {
            return Optional.empty();
        }

        long startedAt = System.currentTimeMillis();
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.chatModel());
            requestBody.put("instructions", getSystemPrompt());
            ArrayNode input = requestBody.putArray("input");
            ObjectNode message = input.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "input_text");
            textPart.put("text", buildUserPrompt(userDescription, usableImages));

            for (ImageInput image : usableImages) {
                AssetService.AssetContent assetContent = assetService.getModelInputContentForUser(userId, image.assetId());
                byte[] bytes;
                String contentType = assetContent.contentType();
                try (var stream = assetContent.stream()) {
                    bytes = stream.readAllBytes();
                }
                ObjectNode imagePart = content.addObject();
                imagePart.put("type", "input_image");
                imagePart.put("image_url", toDataUrl(bytes, contentType));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveResponsesUrl(config.baseUrl())))
                    .timeout(timeout.plusSeconds(5))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "[product-poster-vision] skipped status={} duration={}ms body={}",
                        response.statusCode(),
                        System.currentTimeMillis() - startedAt,
                        preview(response.body())
                );
                return Optional.empty();
            }

            String outputText = ProductIndustryResearchService.extractOutputText(response.body(), objectMapper);
            if (outputText.isBlank()) {
                log.warn("[product-poster-vision] empty duration={}ms", System.currentTimeMillis() - startedAt);
                return Optional.empty();
            }
            VisionAnalysisResult result = parseVisionAnalysis(outputText);
            log.info(
                    "[product-poster-vision] done duration={}ms confirmed={} suggested={}",
                    System.currentTimeMillis() - startedAt,
                    result.confirmedFacts().size(),
                    result.suggestedFacts().size()
            );
            if (result.confirmedFacts().isEmpty() && result.suggestedFacts().isEmpty() && result.summary().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn(
                    "[product-poster-vision] skipped duration={}ms error={}",
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            return Optional.empty();
        }
    }

    private VisionAnalysisResult parseVisionAnalysis(String outputText) throws IOException {
        JsonNode data = objectMapper.readTree(extractJsonObjectText(outputText));
        String summary = safeLength(readText(data.get("summary")), 240);
        List<AiProxyDtos.ProductPosterFact> confirmedFacts = parseFactList(data.get("confirmedFacts"), true);
        List<AiProxyDtos.ProductPosterFact> suggestedFacts = parseFactList(data.get("suggestedFacts"), false);
        return new VisionAnalysisResult(summary, confirmedFacts, suggestedFacts);
    }

    private List<AiProxyDtos.ProductPosterFact> parseFactList(JsonNode node, boolean confirmed) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AiProxyDtos.ProductPosterFact> result = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String key = normalizeFactKey(firstNonBlank(
                    readText(item.get("key")),
                    readText(item.get("field")),
                    readText(item.get("name"))
            ));
            String value = safeLength(readText(item.get("value")), 400);
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            String source = normalizeSource(readText(item.get("source")), confirmed);
            String confidence = normalizeConfidence(readText(item.get("confidence")), confirmed);
            String note = safeLength(firstNonBlank(readText(item.get("note")), readText(item.get("reason"))), 200);
            String dedupeKey = key + "|" + value;
            if (!dedupe.add(dedupeKey)) {
                continue;
            }
            result.add(new AiProxyDtos.ProductPosterFact(
                    key,
                    FACT_LABELS.getOrDefault(key, key),
                    value,
                    source,
                    confidence,
                    note
            ));
        }
        return List.copyOf(result);
    }

    private List<ImageInput> normalizeImages(List<AiProxyDtos.ImageReferenceCandidate> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        Map<UUID, ImageInput> normalized = new LinkedHashMap<>();
        for (AiProxyDtos.ImageReferenceCandidate image : images) {
            if (image == null || isBlank(image.assetId())) {
                continue;
            }
            try {
                UUID assetId = UUID.fromString(image.assetId().trim());
                normalized.putIfAbsent(assetId, new ImageInput(assetId, safeLength(firstNonBlank(image.name(), image.id()), 120)));
                if (normalized.size() >= maxImages) {
                    break;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return List.copyOf(normalized.values());
    }

    private String buildUserPrompt(String userDescription, List<ImageInput> images) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户当前描述：").append(isBlank(userDescription) ? "未提供或只说让你先看图" : userDescription.trim()).append("\n");
        builder.append("当前产品图：\n");
        for (int index = 0; index < images.size(); index += 1) {
            ImageInput image = images.get(index);
            builder.append("- 第 ").append(index + 1).append(" 张图：").append(image.name()).append("\n");
        }
        builder.append("""
                请只基于这些图片做商品信息识别。
                你要把结果分成两类：
                1. confirmedFacts：图片里直接可见、包装/标签/OCR 可直接读出的事实
                2. suggestedFacts：你根据画面风格、包装气质、常见用途做出的高概率建议，但需要用户确认

                只输出严格 JSON，不要 Markdown，不要解释：
                {"summary":"一句话概括你从图里看到的产品","confirmedFacts":[{"key":"productName|industry|material|size|color|style|scenarios|targetAudience|sellingPoints|extraDetails|platformStyle","value":"值","source":"image 或 ocr","confidence":"high 或 medium","note":"不超过40字说明"}],"suggestedFacts":[{"key":"同上","value":"值","source":"inference","confidence":"low 或 medium","note":"不超过40字说明"}]}

                规则：
                - confirmedFacts 只能放直接可见或可读的事实。
                - 如果尺寸、容量、厚度、数量、型号、认证、香调、成分等数值/硬参数看不清，就不要放进 confirmedFacts。
                - suggestedFacts 可以给出行业、目标人群、使用场景、风格调性、卖点方向等候选建议，但必须保守。
                - 不要杜撰具体数值，不要编造品牌授权、销量、功效认证。
                - 如果完全看不出来某项，就省略，不要硬填。
                """);
        return builder.toString();
    }

    private String getSystemPrompt() {
        return "你是一名电商商品图片分析助手，擅长从产品图中区分“已确认事实”和“待用户确认的候选建议”。";
    }

    private static String normalizeFactKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        String normalized = rawKey.trim()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "productname", "name", "title" -> "productName";
            case "industry", "category" -> "industry";
            case "material", "composition", "materialorcomposition" -> "material";
            case "size", "spec", "capacity", "sizeorspec" -> "size";
            case "color", "appearance", "colororappearance" -> "color";
            case "style", "styletone" -> "style";
            case "scenario", "scenarios", "scene", "usescene" -> "scenarios";
            case "targetaudience", "audience", "usergroup" -> "targetAudience";
            case "sellingpoints", "sellingpoint", "benefits", "valuepoints" -> "sellingPoints";
            case "extradetails", "details", "notes", "parameter" -> "extraDetails";
            case "platformstyle", "platform" -> "platformStyle";
            default -> "";
        };
    }

    private static String normalizeSource(String source, boolean confirmed) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("ocr")) return "ocr";
        if (normalized.contains("infer")) return "inference";
        if (normalized.contains("image") || normalized.contains("visual")) return "image";
        return confirmed ? "image" : "inference";
    }

    private static String normalizeConfidence(String value, boolean confirmed) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("high")) return "high";
        if (normalized.contains("low")) return "low";
        if (normalized.contains("medium")) return "medium";
        return confirmed ? "high" : "medium";
    }

    private static String toDataUrl(byte[] bytes, String contentType) {
        String normalizedContentType = isBlank(contentType) ? "image/jpeg" : contentType.trim();
        return "data:%s;base64,%s".formatted(normalizedContentType, Base64.getEncoder().encodeToString(bytes));
    }

    private static String resolveResponsesUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return hasVersionPath(normalized)
                ? normalized + "/responses"
                : normalized + "/v1/responses";
    }

    private static boolean hasVersionPath(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String path = new URI(baseUrl).getPath();
            if (path == null || path.isBlank()) {
                return false;
            }
            return path.replaceAll("/+$", "").matches(".*/v\\d+$");
        } catch (URISyntaxException exception) {
            return baseUrl.toLowerCase(Locale.ROOT).matches(".*/v\\d+$");
        }
    }

    private static String extractJsonObjectText(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String readText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("").trim();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText("").trim();
        }
        return "";
    }

    private static String safeMessage(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null ? "unknown" : throwable.getMessage();
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "上游无响应内容";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 220 ? normalized.substring(0, 220) + "…" : normalized;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safeLength(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ImageInput(UUID assetId, String name) {
    }

    public record VisionAnalysisResult(
            String summary,
            List<AiProxyDtos.ProductPosterFact> confirmedFacts,
            List<AiProxyDtos.ProductPosterFact> suggestedFacts
    ) {
    }
}
