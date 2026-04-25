package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AiProxyService {
    private static final Logger log = LoggerFactory.getLogger(AiProxyService.class);
    private static final Duration IMAGE_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PROMPT_OPTIMIZER_TIMEOUT = Duration.ofMinutes(2);
    private static final int IMAGE_PROXY_MAX_ATTEMPTS = 3;
    private static final List<String> NEGATIVE_PROMPT_TERMS = List.of(
            "画布 UI",
            "工具栏",
            "选中框",
            "控制点",
            "鼠标指针",
            "网格线",
            "截图界面",
            "边框",
            "水印",
            "logo",
            "签名",
            "二维码",
            "条形码",
            "无关文字",
            "字幕",
            "乱码文字",
            "低质量",
            "低清晰度",
            "模糊",
            "失焦",
            "噪点",
            "马赛克",
            "压缩痕迹",
            "JPEG artifacts",
            "过曝",
            "欠曝",
            "色彩脏污",
            "过度锐化",
            "过度平滑",
            "塑料感",
            "AI 痕迹",
            "拼接痕迹",
            "构图混乱",
            "主体被裁切",
            "比例失衡",
            "透视错误",
            "结构扭曲",
            "重复主体",
            "畸形肢体",
            "多余肢体",
            "缺失肢体",
            "多指",
            "少指",
            "手指错误",
            "融合手指",
            "扭曲手部",
            "脸部崩坏",
            "五官错位",
            "眼睛不对称",
            "牙齿异常",
            "表情僵硬"
    );
    private static final String NEGATIVE_PROMPT_TEXT = "负面约束：避免%s。".formatted(String.join("、", NEGATIVE_PROMPT_TERMS));

    private final UserApiConfigService userApiConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiProxyService(UserApiConfigService userApiConfigService, ObjectMapper objectMapper) {
        this.userApiConfigService = userApiConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ResponseEntity<byte[]> proxyImageRequest(
            UUID userId,
            String label,
            String path,
            HttpServletRequest request
    ) throws IOException {
        UserApiConfigDtos.Response config = userApiConfigService.getRequiredConfig(userId);
        byte[] body = request.getInputStream().readAllBytes();
        String targetUrl = joinUrl(config.baseUrl(), path);
        long startedAt = System.currentTimeMillis();
        long deadlineAt = startedAt + IMAGE_REQUEST_TIMEOUT.toMillis();

        log.info("[image-proxy] {} start", label);

        for (int attempt = 1; attempt <= IMAGE_PROXY_MAX_ATTEMPTS; attempt += 1) {
            long remainingTimeoutMs = Math.max(0, deadlineAt - System.currentTimeMillis());
            if (remainingTimeoutMs <= 0) {
                log.error("[image-proxy] {} timeout before attempt={} duration={}ms", label, attempt, System.currentTimeMillis() - startedAt);
                return jsonBytes(HttpStatus.GATEWAY_TIMEOUT, Map.of(
                        "error", "%s upstream request timed out after 10 minutes".formatted(label),
                        "attempts", attempt - 1
                ));
            }

            try {
                log.info("[image-proxy] {} attempt={}/{}", label, attempt, IMAGE_PROXY_MAX_ATTEMPTS);
                HttpRequest upstreamRequest = buildImageRequest(
                        targetUrl,
                        config.apiKey(),
                        request.getHeader(HttpHeaders.ACCEPT),
                        request.getContentType(),
                        body,
                        Duration.ofMillis(remainingTimeoutMs)
                );
                HttpResponse<byte[]> upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
                byte[] responseBody = upstreamResponse.body();
                String requestId = firstHeader(upstreamResponse, "x-oneapi-request-id", "x-request-id", "");

                if (attempt < IMAGE_PROXY_MAX_ATTEMPTS && shouldRetryUpstreamResponse(upstreamResponse.statusCode(), responseBody)) {
                    log.warn("[image-proxy] {} retry status={} attempt={} duration={}ms requestId={} body={}",
                            label,
                            upstreamResponse.statusCode(),
                            attempt,
                            System.currentTimeMillis() - startedAt,
                            requestId.isBlank() ? "-" : requestId,
                            getBodyPreview(responseBody)
                    );
                    sleepBeforeRetry(attempt);
                    continue;
                }

                HttpHeaders headers = new HttpHeaders();
                headers.add(HttpHeaders.CONTENT_TYPE, firstHeader(upstreamResponse, HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8"));
                headers.add(HttpHeaders.CACHE_CONTROL, "no-store");
                headers.add("X-Proxy-Attempts", String.valueOf(attempt));
                if (!requestId.isBlank()) {
                    headers.add("X-Upstream-Request-Id", requestId);
                }

                log.info("[image-proxy] {} done status={} attempts={} duration={}ms requestId={}",
                        label,
                        upstreamResponse.statusCode(),
                        attempt,
                        System.currentTimeMillis() - startedAt,
                        requestId.isBlank() ? "-" : requestId
                );
                return ResponseEntity.status(upstreamResponse.statusCode()).headers(headers).body(responseBody);
            } catch (HttpTimeoutException exception) {
                log.error("[image-proxy] {} timeout attempts={} duration={}ms", label, attempt, System.currentTimeMillis() - startedAt);
                return jsonBytes(HttpStatus.GATEWAY_TIMEOUT, Map.of(
                        "error", "%s upstream request timed out after 10 minutes".formatted(label),
                        "detail", safeMessage(exception),
                        "attempts", attempt
                ));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return jsonBytes(HttpStatus.BAD_GATEWAY, Map.of(
                        "error", "%s upstream request interrupted".formatted(label),
                        "detail", safeMessage(exception),
                        "attempts", attempt
                ));
            } catch (IOException exception) {
                if (attempt < IMAGE_PROXY_MAX_ATTEMPTS) {
                    log.warn("[image-proxy] {} retry error attempt={} duration={}ms error={}", label, attempt, System.currentTimeMillis() - startedAt, safeMessage(exception));
                    sleepBeforeRetry(attempt);
                    continue;
                }

                log.error("[image-proxy] {} failed attempts={} duration={}ms error={}", label, attempt, System.currentTimeMillis() - startedAt, safeMessage(exception));
                return jsonBytes(HttpStatus.BAD_GATEWAY, Map.of(
                        "error", "%s upstream request failed".formatted(label),
                        "detail", safeMessage(exception),
                        "attempts", attempt
                ));
            }
        }

        return jsonBytes(HttpStatus.BAD_GATEWAY, Map.of("error", "%s upstream request failed".formatted(label)));
    }

    public ResponseEntity<Map<String, Object>> optimizePrompt(UUID userId, AiProxyDtos.PromptOptimizeRequest request) {
        UserApiConfigDtos.Response config = userApiConfigService.getRequiredConfig(userId);
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        String mode = "image-to-image".equals(request.mode()) ? "image-to-image" : "text-to-image";

        if (prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入需要优化的提示词"));
        }

        long startedAt = System.currentTimeMillis();
        try {
            String systemPrompt = getPromptOptimizerSystemPrompt(mode);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.chatModel());
            body.put("instructions", systemPrompt);
            body.put("input", "原始提示词：" + prompt);

            log.info("[prompt-optimizer] start mode={} model={}", mode, config.chatModel());
            HttpRequest upstreamRequest = HttpRequest.newBuilder(URI.create(joinUrl(config.baseUrl(), "responses")))
                    .timeout(PROMPT_OPTIMIZER_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (upstreamResponse.statusCode() < 200 || upstreamResponse.statusCode() > 299) {
                return ResponseEntity.status(upstreamResponse.statusCode()).body(Map.of(
                        "error", "提示词优化 responses 接口请求失败",
                        "detail", getBodyPreview(upstreamResponse.body())
                ));
            }

            JsonNode data = objectMapper.readTree(upstreamResponse.body());
            String optimizedPrompt = appendNegativePromptConstraints(sanitizeOptimizedPrompt(extractTextFromAiResponse(data)));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("optimizedPrompt", optimizedPrompt);
            result.put("model", config.chatModel());
            result.put("mode", mode);
            log.info("[prompt-optimizer] done mode={} duration={}ms", mode, System.currentTimeMillis() - startedAt);
            return ResponseEntity.ok(result);
        } catch (HttpTimeoutException exception) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
                    "error", "提示词优化超过 2 分钟，请稍后重试",
                    "detail", safeMessage(exception)
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "提示词优化请求被中断",
                    "detail", safeMessage(exception)
            ));
        } catch (ApiException exception) {
            return ResponseEntity.status(exception.status()).body(Map.of(
                    "error", exception.getMessage(),
                    "code", exception.code()
            ));
        } catch (IOException | RuntimeException exception) {
            log.error("[prompt-optimizer] failed {}", safeMessage(exception));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "提示词优化失败",
                    "detail", safeMessage(exception)
            ));
        }
    }

    private HttpRequest buildImageRequest(
            String targetUrl,
            String apiKey,
            String accept,
            String contentType,
            byte[] body,
            Duration timeout
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(timeout)
                .header(HttpHeaders.ACCEPT, accept == null || accept.isBlank() ? "application/json" : accept)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (contentType != null && !contentType.isBlank()) {
            builder.header(HttpHeaders.CONTENT_TYPE, contentType);
        }

        return builder.build();
    }

    private ResponseEntity<byte[]> jsonBytes(HttpStatus status, Map<String, Object> payload) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        headers.add(HttpHeaders.CACHE_CONTROL, "no-store");
        return ResponseEntity.status(status).headers(headers).body(objectMapper.writeValueAsBytes(payload));
    }

    private boolean shouldRetryUpstreamResponse(int statusCode, byte[] body) {
        if (statusCode < 500 || statusCode > 599) {
            return false;
        }
        String preview = getBodyPreview(body).toLowerCase(Locale.ROOT);
        return preview.contains("stream disconnected")
                || preview.contains("internal_server_error")
                || preview.contains("server_error")
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private String getBodyPreview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private String getPromptOptimizerSystemPrompt(String mode) {
        String sharedRules = """
                你是专业 AI 图像提示词优化器。只输出优化后的提示词，不要解释，不要 Markdown，不要加标题。
                要求：
                - 保留用户原始意图，不新增用户没有要求的主体或文字。
                - 补充清晰的主体、场景、构图、风格、材质、色彩、光影、镜头/视角和质量描述。
                - 必须在提示词末尾加入完整负面约束：%s
                - 使用中文输出，保持一段完整提示词。""".formatted(NEGATIVE_PROMPT_TEXT);

        if ("image-to-image".equals(mode)) {
            return """
                    %s
                    - 当前任务是图生图/重绘，必须强调保留参考图主体身份、结构、比例、构图和关键特征。
                    - 只强化用户要求修改的部分，不要把参考图重写成完全不同画面。""".formatted(sharedRules);
        }

        return """
                %s
                - 当前任务是文生图，需要把短描述扩写成可直接用于高质量图像生成的完整视觉 brief。""".formatted(sharedRules);
    }

    private String sanitizeOptimizedPrompt(String text) {
        return text
                .replaceFirst("(?i)^```(?:text|markdown)?", "")
                .replaceFirst("(?i)```$", "")
                .replaceFirst("(?i)^优化后提示词[:：]\\s*", "")
                .trim();
    }

    private String appendNegativePromptConstraints(String prompt) {
        String trimmedPrompt = prompt.trim();
        if (trimmedPrompt.isBlank() || trimmedPrompt.contains(NEGATIVE_PROMPT_TEXT)) {
            return trimmedPrompt;
        }

        String normalizedPrompt = trimmedPrompt.replaceAll("[。；;，,\\s]+$", "");
        return "%s。%s".formatted(normalizedPrompt, NEGATIVE_PROMPT_TEXT);
    }

    private String extractTextFromAiResponse(JsonNode data) {
        JsonNode outputText = data.get("output_text");
        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }

        JsonNode output = data.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode outputItem : output) {
                JsonNode content = outputItem.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode contentItem : content) {
                        JsonNode text = contentItem.get("text");
                        if (text != null && text.isTextual()) {
                            return text.asText();
                        }
                    }
                }
            }
        }

        JsonNode choices = data.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").get("content");
            if (content != null && content.isTextual()) {
                return content.asText();
            }
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "PROMPT_OPTIMIZER_EMPTY_TEXT", "未能从提示词优化响应中获取文本");
    }

    private String joinUrl(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private String firstHeader(HttpResponse<?> response, String firstName, String fallbackValue) {
        return response.headers().firstValue(firstName).orElse(fallbackValue);
    }

    private String firstHeader(HttpResponse<?> response, String firstName, String secondName, String fallbackValue) {
        return response.headers().firstValue(firstName)
                .or(() -> response.headers().firstValue(secondName))
                .orElse(fallbackValue);
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
