package com.artisanlab.ai;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class ProductIndustryResearchService {
    private static final Logger log = LoggerFactory.getLogger(ProductIndustryResearchService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String configuredSearchModel;
    private final Duration timeout;
    private final HttpClient httpClient;

    public ProductIndustryResearchService(
            ObjectMapper objectMapper,
            @Value("${artisan.ai.product-industry-web-search-enabled:true}") boolean enabled,
            @Value("${artisan.ai.product-industry-web-search-model:}") String configuredSearchModel,
            @Value("${artisan.ai.product-industry-web-search-timeout-seconds:25}") int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.configuredSearchModel = configuredSearchModel == null ? "" : configuredSearchModel.trim();
        this.timeout = Duration.ofSeconds(Math.max(5, Math.min(60, timeoutSeconds)));
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public Optional<IndustryResearchResult> research(
            UserApiConfigDtos.ResolvedConfig config,
            String productInfo
    ) {
        return research(config, productInfo, ResearchProgressListener.noop());
    }

    public Optional<IndustryResearchResult> research(
            UserApiConfigDtos.ResolvedConfig config,
            String productInfo,
            ResearchProgressListener progressListener
    ) {
        String normalizedProductInfo = normalize(productInfo);
        if (!enabled || normalizedProductInfo.isBlank() || config == null || isBlank(config.baseUrl()) || isBlank(config.apiKey())) {
            if (progressListener != null) {
                progressListener.onSkipped("WebSearch 未启用或配置不完整，改用内置行业经验。");
            }
            return Optional.empty();
        }

        long startedAt = System.currentTimeMillis();
        ResearchProgressListener listener = progressListener == null ? ResearchProgressListener.noop() : progressListener;
        try {
            listener.onStarted(safeLength(normalizedProductInfo, 180));
            String requestBody = objectMapper.writeValueAsString(buildRequestBody(config, normalizedProductInfo));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveResponsesUrl(config.baseUrl())))
                    .timeout(timeout.plusSeconds(5))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "[product-industry-web-search] skipped status={} duration={}ms body={}",
                        response.statusCode(),
                        System.currentTimeMillis() - startedAt,
                        preview(response.body())
                );
                listener.onSkipped("WebSearch 调研接口返回 " + response.statusCode() + "，改用内置行业经验。");
                return Optional.empty();
            }

            String outputText = extractOutputText(response.body(), objectMapper);
            List<IndustryResearchSource> sources = extractSources(response.body(), objectMapper);
            if (!sources.isEmpty()) {
                listener.onSources(sources);
            }
            if (outputText.isBlank()) {
                log.warn("[product-industry-web-search] empty duration={}ms", System.currentTimeMillis() - startedAt);
                listener.onSkipped("WebSearch 未返回可用调研内容，改用内置行业经验。");
                return Optional.empty();
            }

            IndustryResearchResult result = new IndustryResearchResult(formatResearchContext(outputText, objectMapper), sources);
            log.info(
                    "[product-industry-web-search] done duration={}ms chars={} sources={}",
                    System.currentTimeMillis() - startedAt,
                    result.context().length(),
                    result.sources().size()
            );
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn(
                    "[product-industry-web-search] skipped duration={}ms error={}",
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            listener.onSkipped("WebSearch 调研失败：" + safeLength(safeMessage(exception), 120));
            return Optional.empty();
        }
    }

    private ObjectNode buildRequestBody(UserApiConfigDtos.ResolvedConfig config, String productInfo) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", configuredSearchModel.isBlank() ? config.chatModel() : configuredSearchModel);
        root.put("input", buildResearchPrompt(productInfo));
        root.put("tool_choice", "required");
        ArrayNode include = root.putArray("include");
        include.add("web_search_call.action.sources");
        ArrayNode tools = root.putArray("tools");
        ObjectNode webSearch = tools.addObject();
        webSearch.put("type", "web_search");
        webSearch.put("search_context_size", "low");
        return root;
    }

    private String buildResearchPrompt(String productInfo) {
        return """
                你是电商详情页行业调研助手。请使用 web search 查找与该产品所属行业相关的近期商品详情页、爆款电商页面、品牌视觉、竞品详情图、社媒种草视觉、广告设计趋势和高转化排版方式，优先总结简洁、优雅、有高级感且转化力强的设计。

                产品信息：
                %s

                只输出严格 JSON，不要 Markdown，不要解释：
                {"industry":"行业/品类","audience":"常见目标人群","visualStyle":"适合该行业的视觉风格","trendStyles":["近期最前沿/最流行/最吸引人的视觉趋势"],"layoutPatterns":["高转化详情图常见版式"],"featureDisplayMethods":["清楚展示产品特点的方法"],"conversionHooks":["能勾起购买欲的视觉和文案钩子"],"copywritingAngles":["适合写在图上的短文案角度"],"propsAndScenes":["适合的场景/道具/光影"],"avoid":["应避免的设计"],"researchSummary":"不超过160字的调研总结"}

                要求：
                - 不要编造具体价格、销量、品牌授权和未经用户提供的参数。
                - 重点输出可以直接指导图片生成提示词的视觉信息，尤其是最前沿、最流行、最引人入胜但仍然简洁优雅的设计风格与排版。
                - 必须说明如何清楚展示产品特点，并如何通过克制的视觉层级、留白、场景、短文案钩子和利益点表达勾起用户购买欲。
                - 避免廉价促销感、花哨爆炸贴、过密文字、过度装饰和低端直播间风格。
                - 如果产品像香水、香氛、珠宝或艺术礼品，强调艺术气息、极简、高级留白、精品画册感和克制文字。
                - 如果产品像食品、美妆、数码、家居、服饰鞋包，请分别提取对应行业的视觉规律。
                """.formatted(productInfo);
    }

    static String extractOutputText(String responseBody, ObjectMapper objectMapper) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        String outputText = readText(root.get("output_text"));
        if (!outputText.isBlank()) {
            return outputText.trim();
        }

        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) {
                    continue;
                }
                for (JsonNode contentItem : content) {
                    String text = readText(contentItem.get("text"));
                    if (!text.isBlank()) {
                        parts.add(text);
                    }
                }
            }
            String joined = String.join("\n", parts).trim();
            if (!joined.isBlank()) {
                return joined;
            }
        }

        return "";
    }

    static String formatResearchContext(String outputText, ObjectMapper objectMapper) {
        String normalized = normalize(outputText);
        if (normalized.isBlank()) {
            return "";
        }

        try {
            JsonNode data = objectMapper.readTree(extractJsonObjectText(normalized));
            List<String> lines = new ArrayList<>();
            appendLine(lines, "行业/品类", readText(data.get("industry")));
            appendLine(lines, "目标人群", readText(data.get("audience")));
            appendLine(lines, "行业视觉风格", readText(data.get("visualStyle")));
            appendLine(lines, "流行趋势风格", readTextArray(data.get("trendStyles")));
            appendLine(lines, "详情页版式", readTextArray(data.get("layoutPatterns")));
            appendLine(lines, "产品特点展示", readTextArray(data.get("featureDisplayMethods")));
            appendLine(lines, "购买欲钩子", readTextArray(data.get("conversionHooks")));
            appendLine(lines, "图中文字角度", readTextArray(data.get("copywritingAngles")));
            appendLine(lines, "场景/道具/光影", readTextArray(data.get("propsAndScenes")));
            appendLine(lines, "避免事项", readTextArray(data.get("avoid")));
            appendLine(lines, "调研总结", readText(data.get("researchSummary")));
            return safeLength(String.join("；", lines), 1800);
        } catch (Exception ignored) {
            return safeLength(normalized, 1800);
        }
    }

    static List<IndustryResearchSource> extractSources(String responseBody, ObjectMapper objectMapper) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        List<IndustryResearchSource> sources = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        collectSources(root, sources, seenUrls);
        return List.copyOf(sources);
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

    private static void appendLine(List<String> lines, String label, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            lines.add(label + "：" + safeLength(normalized, 400));
        }
    }

    private static String readTextArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isArray()) {
            return readText(node);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = readText(item);
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return String.join("、", values);
    }

    private static String readText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static void collectSources(JsonNode node, List<IndustryResearchSource> sources, Set<String> seenUrls) {
        if (node == null || node.isNull() || sources.size() >= 5) {
            return;
        }

        if (node.isObject()) {
            String url = firstText(node, "url", "uri", "link", "source_url", "sourceUrl");
            if (isHttpUrl(url) && seenUrls.add(url)) {
                String title = firstText(node, "title", "name", "display_name", "displayName", "site_name", "siteName");
                sources.add(new IndustryResearchSource(safeLength(normalize(title), 80), safeLength(url.trim(), 500)));
                if (sources.size() >= 5) {
                    return;
                }
            }
            node.fields().forEachRemaining(entry -> collectSources(entry.getValue(), sources, seenUrls));
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectSources(item, sources, seenUrls);
                if (sources.size() >= 5) {
                    return;
                }
            }
        }
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String text = readText(node.get(fieldName));
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String preview(String value) {
        return safeLength(normalize(value), 500);
    }

    private static String safeLength(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private static boolean isHttpUrl(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public record IndustryResearchSource(String title, String url) {
        public IndustryResearchSource {
            title = title == null ? "" : title.trim();
            url = url == null ? "" : url.trim();
        }
    }

    public record IndustryResearchResult(String context, List<IndustryResearchSource> sources) {
        public IndustryResearchResult {
            context = context == null ? "" : context;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public IndustryResearchResult(String context) {
            this(context, List.of());
        }
    }

    public interface ResearchProgressListener {
        default void onStarted(String query) {
        }

        default void onSources(List<IndustryResearchSource> sources) {
        }

        default void onSkipped(String reason) {
        }

        static ResearchProgressListener noop() {
            return new ResearchProgressListener() {
            };
        }
    }
}
