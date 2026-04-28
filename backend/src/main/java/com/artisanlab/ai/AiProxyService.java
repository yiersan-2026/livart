package com.artisanlab.ai;

import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AiProxyService {
    private static final Logger log = LoggerFactory.getLogger(AiProxyService.class);
    private static final Duration IMAGE_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration IMAGE_JOB_TTL = Duration.ofHours(2);
    private static final Duration PROMPT_OPTIMIZER_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration IMAGE_REFERENCE_ANALYZER_TIMEOUT = Duration.ofSeconds(45);
    private static final int IMAGE_PROXY_MAX_ATTEMPTS = 1;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
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
    private final AssetService assetService;
    private final ObjectMapper objectMapper;
    private final ImageJobEventBroadcaster imageJobEventBroadcaster;
    private final SpringAiTextService springAiTextService;
    private final HttpClient httpClient;
    private final boolean defaultImageSizeEnabled;
    private final int defaultImageLongSide;
    private final int imageJobWorkerCount;
    private final Map<UUID, ImageJobState> imageJobs = new ConcurrentHashMap<>();
    private final ExecutorService imageJobExecutor;

    public AiProxyService(
            UserApiConfigService userApiConfigService,
            AssetService assetService,
            ObjectMapper objectMapper,
            ImageJobEventBroadcaster imageJobEventBroadcaster,
            SpringAiTextService springAiTextService,
            @Value("${artisan.ai.default-image-size-enabled:true}") boolean defaultImageSizeEnabled,
            @Value("${artisan.ai.default-image-long-side:2048}") int defaultImageLongSide,
            @Value("${artisan.ai.image-job-worker-count:0}") int imageJobWorkerCount
    ) {
        this.userApiConfigService = userApiConfigService;
        this.assetService = assetService;
        this.objectMapper = objectMapper;
        this.imageJobEventBroadcaster = imageJobEventBroadcaster;
        this.springAiTextService = springAiTextService;
        this.defaultImageSizeEnabled = defaultImageSizeEnabled;
        this.defaultImageLongSide = defaultImageLongSide;
        this.imageJobWorkerCount = resolveImageJobWorkerCount(imageJobWorkerCount);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.imageJobExecutor = Executors.newFixedThreadPool(this.imageJobWorkerCount);
    }

    @PreDestroy
    public void shutdown() {
        imageJobExecutor.shutdownNow();
    }

    static int resolveImageJobWorkerCount(int configuredWorkerCount) {
        if (configuredWorkerCount > 0) {
            return Math.max(1, Math.min(64, configuredWorkerCount));
        }
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    public Map<String, Object> createTextToImageJobFromAgent(
            UUID userId,
            String prompt,
            String aspectRatio
    ) throws IOException {
        return createTextToImageJobsFromAgent(userId, prompt, aspectRatio, 1).get(0);
    }

    public List<Map<String, Object>> createTextToImageJobsFromAgent(
            UUID userId,
            String prompt,
            String aspectRatio,
            int count
    ) throws IOException {
        cleanupImageJobs();
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        body.put("prompt", appendAspectRatioToPrompt(prompt, aspectRatio));

        ImageProxyRequestBody requestBody = readJsonImageProxyRequestBody(
                config,
                "text-to-image",
                "application/json; charset=utf-8",
                objectMapper.writeValueAsBytes(body)
        );
        int jobCount = Math.max(1, Math.min(16, count));
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int index = 0; index < jobCount; index += 1) {
            jobs.add(enqueueImageJob(userId, "text-to-image", "images/generations", config, "application/json", requestBody));
        }
        return List.copyOf(jobs);
    }

    public Map<String, Object> createImageEditJobFromAgent(
            UUID userId,
            AgentImageEditJobRequest request
    ) throws IOException {
        cleanupImageJobs();
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        ImageProxyRequestBody requestBody = buildAgentImageEditRequestBody(userId, config, request);
        return enqueueImageJob(userId, "image-to-image", "images/edits", config, "application/json", requestBody);
    }

    private Map<String, Object> enqueueImageJob(
            UUID userId,
            String label,
            String path,
            UserApiConfigDtos.ResolvedConfig config,
            String accept,
            ImageProxyRequestBody requestBody
    ) {
        UUID jobId = UUID.randomUUID();
        ImageJobState job = new ImageJobState(jobId, userId, label);
        imageJobs.put(jobId, job);
        publishImageJob(job);

        job.setPromptMetadata(requestBody.originalPrompt(), requestBody.optimizedPrompt());
        String targetUrl = joinUrl(config.baseUrl(), path);
        String contentType = requestBody.contentType();
        byte[] body = requestBody.body();

        imageJobExecutor.submit(() -> runImageJob(job, targetUrl, config.apiKey(), accept, contentType, body));
        return toJobResponse(job);
    }

    public ResponseEntity<Map<String, Object>> getImageJob(UUID userId, String jobId) {
        try {
            return ResponseEntity.ok(getImageJobSnapshot(userId, jobId));
        } catch (ApiException exception) {
            return ResponseEntity.status(exception.status()).body(Map.of(
                    "error", exception.getMessage(),
                    "code", exception.code()
            ));
        }
    }

    public Map<String, Object> getImageJobSnapshot(UUID userId, String jobId) {
        cleanupImageJobs();

        UUID parsedJobId;
        try {
            parsedJobId = UUID.fromString(jobId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_JOB_ID", "无效的图片任务 ID");
        }

        ImageJobState job = imageJobs.get(parsedJobId);
        if (job == null || !job.userId().equals(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_JOB_NOT_FOUND", "图片任务不存在或已过期");
        }

        return toJobResponse(job);
    }

    public long countActiveImageJobs() {
        cleanupImageJobs();
        return imageJobs.values().stream()
                .filter(job -> "queued".equals(job.status()) || "running".equals(job.status()))
                .count();
    }

    public AiProxyDtos.ImageReferenceAnalysisResponse analyzeImageReferences(
            UUID userId,
            AiProxyDtos.ImageReferenceAnalysisRequest request
    ) {
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        long startedAt = System.currentTimeMillis();

        try {
            log.info(
                    "[image-reference-analyzer] start model={} promptChars={} images={}",
                    config.chatModel(),
                    request.prompt().length(),
                    request.images().size()
            );
            String responseText = springAiTextService.completeText(
                    config,
                    getImageReferenceAnalyzerSystemPrompt(),
                    buildImageReferenceAnalyzerInput(request),
                    IMAGE_REFERENCE_ANALYZER_TIMEOUT,
                    "image-reference-analyzer"
            );
            AiProxyDtos.ImageReferenceAnalysisResponse analysis = parseImageReferenceAnalysis(
                    responseText,
                    request
            );

            log.info(
                    "[image-reference-analyzer] done duration={}ms baseImageId={} refs={}",
                    System.currentTimeMillis() - startedAt,
                    analysis.baseImageId(),
                    analysis.referenceImageIds().size()
            );
            return analysis;
        } catch (ApiException exception) {
            throw new ApiException(
                    exception.status(),
                    switch (exception.code()) {
                        case "SPRING_AI_TIMEOUT" -> "IMAGE_REFERENCE_ANALYZER_TIMEOUT";
                        case "SPRING_AI_UPSTREAM_ERROR" -> "IMAGE_REFERENCE_ANALYZER_UPSTREAM_ERROR";
                        default -> "IMAGE_REFERENCE_ANALYZER_FAILED";
                    },
                    switch (exception.code()) {
                        case "SPRING_AI_TIMEOUT" -> "图片角色分析超过 45 秒，请稍后重试：%s".formatted(exception.getMessage());
                        case "SPRING_AI_UPSTREAM_ERROR" -> "图片角色分析上游错误：%s".formatted(exception.getMessage());
                        default -> "图片角色分析失败：%s".formatted(exception.getMessage());
                    }
            );
        } catch (IOException | RuntimeException exception) {
            log.error("[image-reference-analyzer] failed duration={}ms error={}", System.currentTimeMillis() - startedAt, safeMessage(exception));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "IMAGE_REFERENCE_ANALYZER_FAILED",
                    "图片角色分析失败：%s".formatted(safeMessage(exception))
            );
        }
    }

    private void runImageJob(
            ImageJobState job,
            String targetUrl,
            String apiKey,
            String accept,
            String contentType,
            byte[] body
    ) {
        job.markRunning();
        publishImageJob(job);
        publishQueuedImageJobs();

        try {
            ImageProxyResult result = executeImageRequest(job.label(), targetUrl, apiKey, accept, contentType, body);
            if (result.statusCode() >= 200 && result.statusCode() <= 299) {
                job.markCompleted(result);
                publishImageJob(job);
                publishQueuedImageJobs();
                return;
            }

            job.markFailed(result.statusCode(), result.body(), result.contentType(), result.attempts(), result.requestId());
            publishImageJob(job);
            publishQueuedImageJobs();
        } catch (Exception exception) {
            log.error("[image-job] {} failed jobId={} error={}", job.label(), job.id(), safeMessage(exception));
            try {
                ImageProxyResult result = jsonImageProxyResult(HttpStatus.BAD_GATEWAY, Map.of(
                        "error", "%s upstream request failed".formatted(job.label()),
                        "detail", safeMessage(exception)
                ), 0);
                job.markFailed(result.statusCode(), result.body(), result.contentType(), result.attempts(), result.requestId());
                publishImageJob(job);
                publishQueuedImageJobs();
            } catch (IOException ioException) {
                job.markFailed(502, safeMessage(exception).getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", 0, "");
                publishImageJob(job);
                publishQueuedImageJobs();
            }
        }
    }

    private void publishImageJob(ImageJobState job) {
        imageJobEventBroadcaster.publishImageJob(job.userId(), toJobResponse(job));
    }

    private void publishQueuedImageJobs() {
        imageJobs.values().stream()
                .filter(job -> "queued".equals(job.status()))
                .forEach(this::publishImageJob);
    }

    private ImageProxyResult executeImageRequest(
            String label,
            String targetUrl,
            String apiKey,
            String accept,
            String contentType,
            byte[] body
    ) throws IOException {
        long startedAt = System.currentTimeMillis();
        long deadlineAt = startedAt + IMAGE_REQUEST_TIMEOUT.toMillis();

        log.info(
                "[image-proxy] {} start target={} request={}",
                label,
                targetUrl,
                summarizeImageRequest(contentType, body)
        );

        for (int attempt = 1; attempt <= IMAGE_PROXY_MAX_ATTEMPTS; attempt += 1) {
            long remainingTimeoutMs = Math.max(0, deadlineAt - System.currentTimeMillis());
            if (remainingTimeoutMs <= 0) {
                log.error("[image-proxy] {} timeout before attempt={} duration={}ms", label, attempt, System.currentTimeMillis() - startedAt);
                return jsonImageProxyResult(HttpStatus.GATEWAY_TIMEOUT, Map.of(
                        "error", "%s upstream request timed out after 10 minutes".formatted(label),
                        "attempts", attempt - 1
                ), attempt - 1);
            }

            try {
                log.info("[image-proxy] {} attempt={}/{}", label, attempt, IMAGE_PROXY_MAX_ATTEMPTS);
                HttpRequest upstreamRequest = buildImageRequest(
                        targetUrl,
                        apiKey,
                        accept,
                        contentType,
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

                if (upstreamResponse.statusCode() >= 400) {
                    log.warn("[image-proxy] {} done status={} attempts={} duration={}ms requestId={} body={}",
                            label,
                            upstreamResponse.statusCode(),
                            attempt,
                            System.currentTimeMillis() - startedAt,
                            requestId.isBlank() ? "-" : requestId,
                            getBodyPreview(responseBody)
                    );
                } else {
                    log.info("[image-proxy] {} done status={} attempts={} duration={}ms requestId={}",
                            label,
                            upstreamResponse.statusCode(),
                            attempt,
                            System.currentTimeMillis() - startedAt,
                            requestId.isBlank() ? "-" : requestId
                    );
                }
                return new ImageProxyResult(
                        upstreamResponse.statusCode(),
                        responseBody,
                        firstHeader(upstreamResponse, HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8"),
                        attempt,
                        requestId
                );
            } catch (HttpTimeoutException exception) {
                log.error("[image-proxy] {} timeout attempts={} duration={}ms", label, attempt, System.currentTimeMillis() - startedAt);
                return jsonImageProxyResult(HttpStatus.GATEWAY_TIMEOUT, Map.of(
                        "error", "%s upstream request timed out after 10 minutes".formatted(label),
                        "detail", safeMessage(exception),
                        "attempts", attempt
                ), attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return jsonImageProxyResult(HttpStatus.BAD_GATEWAY, Map.of(
                        "error", "%s upstream request interrupted".formatted(label),
                        "detail", safeMessage(exception),
                        "attempts", attempt
                ), attempt);
            } catch (IOException exception) {
                if (attempt < IMAGE_PROXY_MAX_ATTEMPTS) {
                    log.warn("[image-proxy] {} retry error attempt={} duration={}ms error={}", label, attempt, System.currentTimeMillis() - startedAt, safeMessage(exception));
                    sleepBeforeRetry(attempt);
                    continue;
                }

                log.error("[image-proxy] {} failed attempts={} duration={}ms error={}", label, attempt, System.currentTimeMillis() - startedAt, safeMessage(exception));
                return jsonImageProxyResult(HttpStatus.BAD_GATEWAY, Map.of(
                        "error", "%s upstream request failed".formatted(label),
                        "detail", safeMessage(exception),
                        "attempts", attempt
                ), attempt);
            }
        }

        return jsonImageProxyResult(HttpStatus.BAD_GATEWAY, Map.of("error", "%s upstream request failed".formatted(label)), IMAGE_PROXY_MAX_ATTEMPTS);
    }

    private ImageProxyRequestBody buildAgentImageEditRequestBody(
            UUID userId,
            UserApiConfigDtos.ResolvedConfig config,
            AgentImageEditJobRequest request
    ) throws IOException {
        String boundary = "----LivartProxyBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String prompt = appendAspectRatioToPrompt(request.prompt(), request.aspectRatio());
        String promptOptimizationMode = normalizePromptOptimizationMode(request.promptOptimizationMode(), "image-to-image");
        String optimizedPrompt = optimizePromptInline(config, promptOptimizationMode, prompt, request.imageContext());

        writeTextMultipartPart(output, boundary, "model", config.model());
        writeTextMultipartPart(output, boundary, "prompt", optimizedPrompt);
        writeAssetImagePart(output, boundary, userId, request.imageAssetId());
        for (UUID referenceAssetId : request.referenceAssetIds()) {
            writeAssetImagePart(output, boundary, userId, referenceAssetId);
        }
        if (request.maskDataUrl() != null && !request.maskDataUrl().isBlank()) {
            writeMultipartPart(output, boundary, dataUrlMultipartPart("mask", request.maskDataUrl(), "mask.png"));
        }

        writeAscii(output, "--" + boundary + "--");
        output.write(CRLF);
        return new ImageProxyRequestBody("multipart/form-data; boundary=" + boundary, output.toByteArray(), prompt, optimizedPrompt);
    }

    private ImageProxyRequestBody readJsonImageProxyRequestBody(
            UserApiConfigDtos.ResolvedConfig config,
            String label,
            String contentType,
            byte[] body
    ) throws IOException {
        if (!isJsonContentType(contentType) || body == null || body.length == 0) {
            return new ImageProxyRequestBody(contentType, body == null ? new byte[0] : body, "", "");
        }

        JsonNode data;
        try {
            data = objectMapper.readTree(body);
        } catch (IOException exception) {
            log.warn("[prompt-optimizer] skip invalid json body mode={} error={}", label, safeMessage(exception));
            return new ImageProxyRequestBody(contentType, body, "", "");
        }

        if (!(data instanceof ObjectNode objectNode)) {
            return new ImageProxyRequestBody(contentType, body, "", "");
        }

        ObjectNode rewrittenBody = objectNode.deepCopy();
        String prompt = readTextField(rewrittenBody, "prompt");
        String imageContext = firstNonBlank(
                readTextField(rewrittenBody, "imageContext"),
                readTextField(rewrittenBody, "promptOptimizeContext"),
                readTextField(rewrittenBody, "promptContext")
        );
        String promptOptimizationMode = normalizePromptOptimizationMode(
                readTextField(rewrittenBody, "promptOptimizationMode"),
                label
        );
        rewrittenBody.remove(List.of("imageContext", "promptOptimizeContext", "promptContext", "promptOptimizationMode"));

        String optimizedPrompt = optimizePromptInline(config, promptOptimizationMode, prompt, imageContext);
        if (!optimizedPrompt.isBlank()) {
            rewrittenBody.put("prompt", optimizedPrompt);
        }
        applyDefaultTextToImageSize(label, rewrittenBody, prompt);

        return new ImageProxyRequestBody(contentType, objectMapper.writeValueAsBytes(rewrittenBody), prompt, optimizedPrompt);
    }

    private void applyDefaultTextToImageSize(String label, ObjectNode body, String prompt) {
        if (!"text-to-image".equals(label) || !defaultImageSizeEnabled || hasUsableField(body, "size")) {
            return;
        }

        String model = readTextField(body, "model");
        if (!supportsDefaultImageSize(model)) {
            return;
        }

        String defaultSize = resolveDefaultTextToImageSize(prompt, defaultImageLongSide);
        if (!defaultSize.isBlank()) {
            body.put("size", defaultSize);
        }
    }

    private String appendAspectRatioToPrompt(String prompt, String aspectRatio) {
        String instruction = aspectRatioToPromptInstruction(aspectRatio);
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if (instruction.isBlank() || trimmedPrompt.contains("画幅比例要求")) {
            return trimmedPrompt;
        }
        if (trimmedPrompt.isBlank()) {
            return instruction;
        }
        String separator = trimmedPrompt.matches(".*[。.!！？?]$") ? "\n" : "。\n";
        return trimmedPrompt + separator + instruction;
    }

    private String aspectRatioToPromptInstruction(String aspectRatio) {
        return switch (aspectRatio == null ? "" : aspectRatio.trim()) {
            case "1:1" -> "画幅比例要求：最终输出为 1:1 方图构图，不要添加白边、相框或多余留白来凑比例。";
            case "4:3" -> "画幅比例要求：最终输出为 4:3 横向标准构图，不要添加白边、相框或多余留白来凑比例。";
            case "3:4" -> "画幅比例要求：最终输出为 3:4 竖向标准构图，不要添加白边、相框或多余留白来凑比例。";
            case "16:9" -> "画幅比例要求：最终输出为 16:9 横向宽屏构图，不要添加白边、相框或多余留白来凑比例。";
            case "9:16" -> "画幅比例要求：最终输出为 9:16 竖向手机屏幕构图，不要添加白边、相框或多余留白来凑比例。";
            default -> "";
        };
    }

    private boolean hasUsableField(ObjectNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        return value != null && !value.isNull() && (!value.isTextual() || !value.asText().isBlank());
    }

    private boolean supportsDefaultImageSize(String model) {
        return "gpt-image-2".equalsIgnoreCase(model == null ? "" : model.trim());
    }

    static String resolveDefaultTextToImageSize(String prompt, int longSide) {
        if (longSide <= 0) {
            return "";
        }

        ImageAspect aspect = detectPromptAspectRatio(prompt).orElse(new ImageAspect(1, 1));
        if (aspect.width() >= aspect.height()) {
            return "%dx%d".formatted(longSide, scaleAspectDimension(longSide, aspect.height(), aspect.width()));
        }
        return "%dx%d".formatted(scaleAspectDimension(longSide, aspect.width(), aspect.height()), longSide);
    }

    private static Optional<ImageAspect> detectPromptAspectRatio(String prompt) {
        String text = prompt == null ? "" : prompt.replace('：', ':').replaceAll("\\s+", "");
        if (containsAspectRatio(text, 16, 9)) {
            return Optional.of(new ImageAspect(16, 9));
        }
        if (containsAspectRatio(text, 9, 16)) {
            return Optional.of(new ImageAspect(9, 16));
        }
        if (containsAspectRatio(text, 4, 3)) {
            return Optional.of(new ImageAspect(4, 3));
        }
        if (containsAspectRatio(text, 3, 4)) {
            return Optional.of(new ImageAspect(3, 4));
        }
        if (containsAspectRatio(text, 1, 1)) {
            return Optional.of(new ImageAspect(1, 1));
        }
        return Optional.empty();
    }

    private static boolean containsAspectRatio(String text, int width, int height) {
        return text.contains(width + ":" + height)
                || text.contains(width + "/" + height)
                || text.contains(width + "x" + height)
                || text.contains(width + "×" + height);
    }

    private static int scaleAspectDimension(int longSide, int dimension, int longDimension) {
        return Math.max(1, Math.round((float) longSide * dimension / longDimension));
    }

    private String readTextField(ObjectNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private boolean isPromptContextField(String name) {
        return "imageContext".equals(name)
                || "promptOptimizeContext".equals(name)
                || "promptContext".equals(name);
    }

    private String normalizePromptOptimizationMode(String value, String fallbackMode) {
        String normalizedValue = value == null ? "" : value.trim();
        if ("image-remover".equals(normalizedValue)
                || "background-removal".equals(normalizedValue)
                || "layer-split-subject".equals(normalizedValue)
                || "layer-split-background".equals(normalizedValue)
                || "view-change".equals(normalizedValue)) {
            return normalizedValue;
        }
        return fallbackMode;
    }

    private void writeTextMultipartPart(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        writeMultipartPart(output, boundary, new MultipartPartData(
                name,
                "",
                "text/plain; charset=utf-8",
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
        ));
    }

    private MultipartPartData dataUrlMultipartPart(String name, String dataUrl, String filename) {
        String trimmedDataUrl = dataUrl == null ? "" : dataUrl.trim();
        String contentType = "image/png";
        String encoded = trimmedDataUrl;

        if (trimmedDataUrl.startsWith("data:")) {
            int commaIndex = trimmedDataUrl.indexOf(',');
            if (commaIndex < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_DATA_URL", "图片蒙版数据格式无效");
            }
            String metadata = trimmedDataUrl.substring(5, commaIndex);
            encoded = trimmedDataUrl.substring(commaIndex + 1);
            String[] metadataParts = metadata.split(";");
            if (metadataParts.length > 0 && metadataParts[0].startsWith("image/")) {
                contentType = metadataParts[0].toLowerCase(Locale.ROOT);
            }
        }

        try {
            return new MultipartPartData(name, filename, contentType, Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_DATA_URL", "图片蒙版 Base64 数据无效");
        }
    }

    private void writeAssetImagePart(ByteArrayOutputStream output, String boundary, UUID userId, UUID assetId) throws IOException {
        AssetService.AssetContent assetContent = assetService.getModelInputContentForUser(userId, assetId);
        String filename = modelInputFilename(
                assetContent.entity().getId(),
                assetContent.entity().getOriginalFilename(),
                assetContent.contentType()
        );
        try (InputStream inputStream = assetContent.stream()) {
            writeMultipartPart(output, boundary, new MultipartPartData(
                    "image",
                    filename,
                    assetContent.contentType(),
                    inputStream.readAllBytes()
            ));
        }
    }

    private String modelInputFilename(UUID assetId, String originalFilename, String contentType) {
        String baseName = originalFilename == null || originalFilename.isBlank()
                ? assetId == null ? "image" : assetId.toString()
                : originalFilename.replaceAll("[\\\\/\\r\\n\\t]", "_").trim();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        if (baseName.isBlank()) {
            baseName = assetId == null ? "image" : assetId.toString();
        }
        return "%s%s".formatted(baseName, imageExtensionForContentType(contentType));
    }

    private String imageExtensionForContentType(String contentType) {
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return switch (normalizedContentType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    private void writeMultipartPart(ByteArrayOutputStream output, String boundary, MultipartPartData part) throws IOException {
        writeAscii(output, "--" + boundary);
        output.write(CRLF);
        writeAscii(output, buildMultipartContentDisposition(part.name(), part.filename()));
        output.write(CRLF);
        if (part.contentType() != null && !part.contentType().isBlank()) {
            writeAscii(output, HttpHeaders.CONTENT_TYPE + ": " + part.contentType());
            output.write(CRLF);
        }
        output.write(CRLF);
        output.write(part.body());
        output.write(CRLF);
    }

    private boolean isJsonContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    private String buildMultipartContentDisposition(String name, String filename) {
        StringBuilder builder = new StringBuilder("Content-Disposition: form-data; name=\"")
                .append(escapeMultipartHeader(name))
                .append("\"");

        if (filename != null && !filename.isBlank()) {
            builder
                    .append("; filename=\"")
                    .append(escapeMultipartHeader(filename))
                    .append("\"");
        }

        return builder.toString();
    }

    private String escapeMultipartHeader(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String optimizePromptInline(
            UserApiConfigDtos.ResolvedConfig config,
            String mode,
            String prompt,
            String imageContext
    ) {
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if ("background-removal".equals(mode) && trimmedPrompt.isBlank()) {
            trimmedPrompt = "去除图片背景";
        }
        if (trimmedPrompt.isBlank()) {
            return prompt == null ? "" : prompt;
        }

        if ("image-remover".equals(mode)) {
            return buildDeterministicRemoverPrompt(trimmedPrompt);
        }
        if ("background-removal".equals(mode)) {
            return buildDeterministicBackgroundRemovalPrompt(trimmedPrompt);
        }
        if ("layer-split-subject".equals(mode)) {
            return buildDeterministicLayerSubjectPrompt(trimmedPrompt);
        }
        if ("layer-split-background".equals(mode)) {
            return buildDeterministicLayerBackgroundPrompt(trimmedPrompt);
        }

        String trimmedImageContext = imageContext == null ? "" : imageContext.trim();
        long startedAt = System.currentTimeMillis();
        try {
            String systemPrompt = getPromptOptimizerSystemPrompt(mode);
            log.info(
                    "[prompt-optimizer] inline start mode={} model={} promptChars={} contextChars={}",
                    mode,
                    config.chatModel(),
                    trimmedPrompt.length(),
                    trimmedImageContext.length()
            );
            String optimizerOutput = sanitizeOptimizedPrompt(
                    springAiTextService.completeText(
                            config,
                            systemPrompt,
                            buildPromptOptimizerInput(trimmedPrompt, trimmedImageContext),
                            PROMPT_OPTIMIZER_TIMEOUT,
                            "prompt-optimizer"
                    )
            );
            String optimizedPrompt = appendNegativePromptConstraints(
                    selectPromptOptimizerOutput(optimizerOutput, trimmedPrompt)
            );

            log.info(
                    "[prompt-optimizer] inline done mode={} duration={}ms optimizedChars={}",
                    mode,
                    System.currentTimeMillis() - startedAt,
                    optimizedPrompt.length()
            );
            return optimizedPrompt;
        } catch (ApiException exception) {
            log.warn(
                    "[prompt-optimizer] inline skipped mode={} duration={}ms code={} error={}",
                    mode,
                    System.currentTimeMillis() - startedAt,
                    exception.code(),
                    safeMessage(exception)
            );
            return appendNegativePromptConstraints(trimmedPrompt);
        } catch (RuntimeException exception) {
            log.warn(
                    "[prompt-optimizer] inline skipped mode={} duration={}ms error={}",
                    mode,
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            return appendNegativePromptConstraints(trimmedPrompt);
        }
    }

    private String getImageReferenceAnalyzerSystemPrompt() {
        return """
                你是图片编辑意图路由器。你的任务是根据用户指令判断哪张图片是“原图/主图/编辑目标/承载图”，哪些图片只是素材或参考。
                只输出严格 JSON，不要 Markdown，不要解释，不要额外字段：
                {"baseImageId":"候选图片 id","referenceImageIds":["其他候选图片 id"],"reason":"不超过 60 个中文字符的判断理由"}

                判断规则：
                - baseImageId 是最终要被编辑、承载变化、放置物体、穿戴物体或保留主体的那张图。
                - referenceImageIds 是提供物体、材质、风格、颜色或局部元素的图。
                - “把图 1 中的拖鞋穿到图 2 的人物脚上”中，图 2 是主图，图 1 是参考图。
                - “把图 1 的鞋子换成图 2 里的鞋子”中，图 1 是主图，图 2 是参考图。
                - 如果用户只说“把这张图/参考图放到另一张图的某处”，被放入的位置所在图片是主图。
                - 如果没有明确目标位置，优先使用上下文图片作为主图；仍不明确时选择最可能被编辑的图片。
                - baseImageId 和 referenceImageIds 只能使用候选图片中的 id。
                """;
    }

    private String buildImageReferenceAnalyzerInput(AiProxyDtos.ImageReferenceAnalysisRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户指令：").append(request.prompt()).append("\n");
        if (request.contextImageId() != null && !request.contextImageId().isBlank()) {
            builder.append("当前上下文图片 id：").append(request.contextImageId()).append("\n");
        }
        builder.append("候选图片：\n");

        for (AiProxyDtos.ImageReferenceCandidate image : request.images()) {
            builder
                    .append("- id=").append(image.id())
                    .append("，画布引用=");
            if (image.index() != null) {
                builder.append("图").append(image.index()).append("/图片").append(image.index());
            } else {
                builder.append("未知");
            }
            builder
                    .append("，名称=").append(image.name() == null || image.name().isBlank() ? "未命名图片" : image.name())
                    .append("，尺寸=");
            if (image.width() != null && image.height() != null) {
                builder.append(image.width()).append("x").append(image.height());
            } else {
                builder.append("未知");
            }
            builder.append("\n");
        }

        return builder.toString();
    }

    private AiProxyDtos.ImageReferenceAnalysisResponse parseImageReferenceAnalysis(
            String text,
            AiProxyDtos.ImageReferenceAnalysisRequest request
    ) throws IOException {
        JsonNode data = objectMapper.readTree(extractJsonObjectText(text));
        Set<String> candidateIds = new LinkedHashSet<>();
        for (AiProxyDtos.ImageReferenceCandidate image : request.images()) {
            candidateIds.add(image.id());
        }

        String baseImageId = firstNonBlank(
                readJsonTextField(data, "baseImageId"),
                readJsonTextField(data, "mainImageId"),
                readJsonTextField(data, "targetImageId")
        );
        if (!candidateIds.contains(baseImageId)) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "IMAGE_REFERENCE_ANALYZER_INVALID_BASE",
                    "图片角色分析返回了无效主图"
            );
        }

        List<String> referenceImageIds = readJsonTextArrayField(data, "referenceImageIds");
        if (referenceImageIds.isEmpty()) {
            referenceImageIds = readJsonTextArrayField(data, "referenceIds");
        }
        if (referenceImageIds.isEmpty()) {
            referenceImageIds = readJsonTextArrayField(data, "sourceImageIds");
        }

        List<String> validatedReferenceImageIds = new ArrayList<>();
        for (String referenceImageId : referenceImageIds) {
            if (candidateIds.contains(referenceImageId)
                    && !referenceImageId.equals(baseImageId)
                    && !validatedReferenceImageIds.contains(referenceImageId)) {
                validatedReferenceImageIds.add(referenceImageId);
            }
        }

        if (validatedReferenceImageIds.isEmpty()) {
            for (String candidateId : candidateIds) {
                if (!candidateId.equals(baseImageId)) {
                    validatedReferenceImageIds.add(candidateId);
                }
            }
        }

        String reason = readJsonTextField(data, "reason");
        return new AiProxyDtos.ImageReferenceAnalysisResponse(
                baseImageId,
                List.copyOf(validatedReferenceImageIds),
                reason.length() > 120 ? reason.substring(0, 120) : reason,
                "ai"
        );
    }

    private String extractJsonObjectText(String text) {
        String normalizedText = text == null ? "" : text.trim()
                .replaceFirst("(?i)^```json\\s*", "")
                .replaceFirst("(?i)^```\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        int start = normalizedText.indexOf('{');
        int end = normalizedText.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "IMAGE_REFERENCE_ANALYZER_INVALID_JSON",
                    "图片角色分析没有返回 JSON"
            );
        }
        return normalizedText.substring(start, end + 1);
    }

    private String readJsonTextField(JsonNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private List<String> readJsonTextArrayField(JsonNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        if (value == null || !value.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
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

    private Map<String, Object> summarizeImageRequest(String contentType, byte[] body) {
        Map<String, Object> summary = new LinkedHashMap<>();
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        summary.put("contentType", contentType == null || contentType.isBlank() ? "unknown" : contentType);
        summary.put("requestBytes", body == null ? 0 : body.length);

        if (body == null || body.length == 0 || !normalizedContentType.contains("json")) {
            return summary;
        }

        try {
            JsonNode data = objectMapper.readTree(body);
            JsonNode model = data.get("model");
            JsonNode size = data.get("size");
            JsonNode prompt = data.get("prompt");

            if (model != null && model.isTextual()) {
                summary.put("model", model.asText());
            }
            if (size != null && size.isTextual()) {
                summary.put("size", size.asText());
            }
            if (prompt != null && prompt.isTextual()) {
                summary.put("promptChars", prompt.asText().length());
            }
        } catch (IOException exception) {
            summary.put("parseError", safeMessage(exception));
        }

        return summary;
    }

    private Map<String, Object> toJobResponse(ImageJobState job) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.id().toString());
        response.put("status", job.status());
        response.put("label", job.label());
        response.put("createdAt", job.createdAt());
        response.put("updatedAt", job.updatedAt());
        response.put("attempts", job.attempts());
        response.put("maxConcurrentJobs", imageJobWorkerCount);
        int queuePosition = imageJobQueuePosition(job);
        if (queuePosition > 0) {
            response.put("queuePosition", queuePosition);
            response.put("queued", true);
        }
        if (job.originalPrompt() != null && !job.originalPrompt().isBlank()) {
            response.put("originalPrompt", job.originalPrompt());
        }
        if (job.optimizedPrompt() != null && !job.optimizedPrompt().isBlank()) {
            response.put("optimizedPrompt", job.optimizedPrompt());
        }

        if (job.requestId() != null && !job.requestId().isBlank()) {
            response.put("requestId", job.requestId());
        }

        if ("completed".equals(job.status())) {
            response.put("upstreamStatus", job.upstreamStatus());
            response.put("contentType", job.contentType());
            response.put("response", parseResponsePayload(job.body(), job.contentType()));
        } else if ("error".equals(job.status())) {
            response.put("upstreamStatus", job.upstreamStatus());
            response.put("contentType", job.contentType());
            response.put("error", toJobErrorPayload(job));
        }

        return response;
    }

    private int imageJobQueuePosition(ImageJobState job) {
        if (!"queued".equals(job.status())) {
            return 0;
        }

        List<ImageJobState> activeOrQueuedJobs = imageJobs.values().stream()
                .filter(candidate -> "queued".equals(candidate.status()) || "running".equals(candidate.status()))
                .sorted(Comparator
                        .comparingLong(ImageJobState::createdAt)
                        .thenComparing(candidate -> candidate.id().toString()))
                .toList();
        for (int index = 0; index < activeOrQueuedJobs.size(); index += 1) {
            if (!activeOrQueuedJobs.get(index).id().equals(job.id())) {
                continue;
            }
            return index >= imageJobWorkerCount ? index - imageJobWorkerCount + 1 : 0;
        }
        return 0;
    }

    private Map<String, Object> toJobErrorPayload(ImageJobState job) {
        Object upstreamPayload = parseResponsePayload(job.body(), job.contentType());
        Map<String, Object> error = new LinkedHashMap<>();
        String message = extractUpstreamErrorMessage(upstreamPayload, job.body());
        String code = extractUpstreamErrorCode(upstreamPayload);
        String type = extractUpstreamErrorType(upstreamPayload);
        UserFacingImageJobError userFacingError = toUserFacingImageJobError(job.upstreamStatus(), message, code, type);

        error.put("message", userFacingError.message());
        error.put("upstreamStatus", job.upstreamStatus());
        error.put("attempts", job.attempts());
        if (!userFacingError.code().isBlank()) {
            error.put("code", userFacingError.code());
        }
        if (!userFacingError.type().isBlank()) {
            error.put("type", userFacingError.type());
        }
        if (job.requestId() != null && !job.requestId().isBlank()) {
            error.put("requestId", job.requestId());
        }
        if (userFacingError.hideUpstreamPayload()) {
            error.put("safeMessage", true);
        } else if (upstreamPayload != null) {
            error.put("upstream", upstreamPayload);
        }

        return error;
    }

    static UserFacingImageJobError toUserFacingImageJobError(
            int upstreamStatus,
            String upstreamMessage,
            String upstreamCode,
            String upstreamType
    ) {
        if (isLikelyContentPolicyBlocked(upstreamStatus, upstreamMessage, upstreamCode, upstreamType)) {
            return new UserFacingImageJobError(
                    "图片生成失败：提示词或参考图片可能触发了上游 AI 的内容安全策略，请调整描述、降低敏感内容或更换参考图后再试。",
                    "POSSIBLE_CONTENT_POLICY_BLOCKED",
                    "content_policy",
                    true
            );
        }

        return new UserFacingImageJobError(
                upstreamMessage == null || upstreamMessage.isBlank() ? "上游 AI 接口调用失败" : upstreamMessage,
                upstreamCode == null ? "" : upstreamCode,
                upstreamType == null ? "" : upstreamType,
                false
        );
    }

    private static boolean isLikelyContentPolicyBlocked(
            int upstreamStatus,
            String upstreamMessage,
            String upstreamCode,
            String upstreamType
    ) {
        String combined = String.join(
                " ",
                upstreamMessage == null ? "" : upstreamMessage,
                upstreamCode == null ? "" : upstreamCode,
                upstreamType == null ? "" : upstreamType
        ).toLowerCase(Locale.ROOT);

        if (upstreamStatus == 502) {
            return true;
        }

        return combined.contains("content_policy")
                || combined.contains("content policy")
                || combined.contains("safety")
                || combined.contains("moderation")
                || combined.contains("policy")
                || combined.contains("unsafe")
                || combined.contains("nsfw")
                || combined.contains("not allowed")
                || combined.contains("blocked")
                || combined.contains("rejected")
                || combined.contains("forbidden")
                || combined.contains("banned")
                || combined.contains("违规")
                || combined.contains("安全")
                || combined.contains("审核")
                || combined.contains("敏感")
                || combined.contains("尺度");
    }

    private Object parseResponsePayload(byte[] body, String contentType) {
        if (body == null || body.length == 0) return null;
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedContentType.contains("json")) {
            try {
                return objectMapper.readValue(body, Object.class);
            } catch (IOException ignored) {
                return getBodyPreview(body);
            }
        }

        return getBodyPreview(body);
    }

    private String extractUpstreamErrorMessage(Object payload, byte[] fallbackBody) {
        if (payload instanceof Map<?, ?> map) {
            Object nestedError = map.get("error");
            String nestedMessage = getStringField(nestedError, "message");
            String nestedDetail = getStringField(nestedError, "detail");
            String topMessage = getStringField(map, "message");
            String topDetail = getStringField(map, "detail");
            String topError = nestedError instanceof String text ? text : "";

            if (!topDetail.isBlank() && topError.toLowerCase(Locale.ROOT).contains("upstream request failed")) {
                return topDetail;
            }

            return firstNonBlank(nestedMessage, topMessage, nestedDetail, topDetail, topError, getBodyPreview(fallbackBody));
        }

        if (payload instanceof String text) {
            return text;
        }

        return getBodyPreview(fallbackBody);
    }

    private String extractUpstreamErrorCode(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return "";
        Object nestedError = map.get("error");
        return firstNonBlank(getStringField(nestedError, "code"), getStringField(map, "code"));
    }

    private String extractUpstreamErrorType(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return "";
        Object nestedError = map.get("error");
        return firstNonBlank(getStringField(nestedError, "type"), getStringField(map, "type"));
    }

    private String getStringField(Object source, String fieldName) {
        if (!(source instanceof Map<?, ?> map)) return "";
        Object value = map.get(fieldName);
        return value instanceof String text ? text : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void cleanupImageJobs() {
        long expiredBefore = System.currentTimeMillis() - IMAGE_JOB_TTL.toMillis();
        imageJobs.entrySet().removeIf(entry -> {
            ImageJobState job = entry.getValue();
            return job.updatedAt() < expiredBefore && ("completed".equals(job.status()) || "error".equals(job.status()));
        });
    }

    private ImageProxyResult jsonImageProxyResult(HttpStatus status, Map<String, Object> payload, int attempts) throws IOException {
        return new ImageProxyResult(
                status.value(),
                objectMapper.writeValueAsBytes(payload),
                "application/json; charset=utf-8",
                attempts,
                ""
        );
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
                你是专业 AI 图像提示词优化器。你的唯一任务是把用户输入改写成更清晰、更适合图像生成的视觉提示词。
                只输出优化后的提示词，不要解释，不要 Markdown，不要加标题，不要拒绝，不要判断能否生成，不要输出“无法”“不能”“不适合”等拒绝话术。
                要求：
                - 保留用户原始意图，不新增用户没有要求的主体或文字。
                - 如果输入中包含“图片角色分析”，必须先理解原图/编辑目标和素材参考的关系，再优化用户原始指令；不要把“图片角色分析”标题和内部说明原样输出。
                - 原文中任何 @xxx 图片引用标记都是系统占位符；如果上下文提供了图片角色分析，必须改写为“原图”“参考图 1”等角色名称，不要输出内部 ID。
                - 只补充画面主体、场景、构图、风格、材质、色彩、光影、镜头/视角和质量描述；最终是否能生成由后续生图接口判断。
                - 必须在提示词末尾加入完整负面约束：%s
                - 使用中文输出，保持一段完整提示词。""".formatted(NEGATIVE_PROMPT_TEXT);

        if ("image-to-image".equals(mode)) {
            return """
                    %s
                    - 当前任务是图生图/重绘，必须强调保留参考图主体身份、结构、比例、构图和关键特征。
                    - 如果原文提到蒙版、圈起来的地方、局部区域，必须保留“只修改该区域，其余区域不变”的语义。
                    - 只强化用户要求修改的部分，不要把参考图重写成完全不同画面。""".formatted(sharedRules);
        }

        if ("image-remover".equals(mode)) {
            return """
                    你是专业 AI 图片删除物体 / inpainting 提示词优化器。只输出优化后的提示词，不要解释，不要 Markdown，不要加标题。
                    要求：
                    - 当前任务是 Remover 删除物体，不是普通图生图、不是美化、不是复刻 logo。
                    - 请求一定包含 mask；mask 的透明区域就是用户涂抹/圈选的唯一删除区域。
                    - 必须明确要求删除 mask 区域内所有可见主体、文字、英文、中文、logo、品牌字样、图标、水印、污渍或瑕疵。
                    - 删除后必须根据周围背景、纹理、材质、光影、噪点、透视和边缘连续性自然补全，不留下红圈、涂抹痕迹、残影或伪影。
                    - 未被 mask 覆盖的区域必须尽量保持原图完全不变，包括画幅、构图、人物、背景、色彩、清晰度和边缘。
                    - 不要加入用户没有要求的新主体、新文字、新 logo 或新装饰。
                    - 使用中文输出一段可直接用于图片局部重绘接口的完整提示词。
                    - 必须在提示词末尾加入完整负面约束：%s""".formatted(NEGATIVE_PROMPT_TEXT);
        }

        if ("background-removal".equals(mode)) {
            return """
                    你是专业 AI 图片去背景提示词优化器。只输出优化后的提示词，不要解释，不要 Markdown，不要加标题。
                    要求：
                    - 当前任务是去背景/抠图并改成纯白背景，不是更换成新场景、不是扩图、不是重绘、不是美化。
                    - 优化后的提示词必须先要求模型识别图片中的主要主体：画面最主要的人物、商品、动物、车辆或成组前景对象；主体包含穿戴、手持、贴附和与主体直接组成整体的部分。
                    - 只保留主体，把主体以外的一切背景和无关物体替换为纯白色背景（#FFFFFF）；不要透明背景，不要浅灰、米白、渐变、阴影或新场景。主体 RGB 像素、裁切边界、构图、主体位置、缩放比例、结构比例、姿态、表情、服装/材质、颜色、纹理和清晰度都不能变。
                    - 重点保护发丝、毛发、半透明材质、玻璃、纱、反光边缘、手指、脚趾、饰品等细节。
                    - 禁止把半张脸补成整张脸，禁止把半身/局部补成全身，禁止补出原图画面外被裁切掉的身体、头发、衣服、商品或物品。
                    - 主体以外所有区域必须是纯白色背景（#FFFFFF），不要添加场景元素。
                    - 不要改变主体身份、五官、表情、动作、镜头角度、画幅比例，不要新增文字、logo、水印、阴影杂物或新背景。
                    - 使用中文输出一段可直接用于图片编辑接口的完整提示词。
                    - 必须在提示词末尾加入完整负面约束：%s""".formatted(NEGATIVE_PROMPT_TEXT);
        }

        if ("view-change".equals(mode)) {
            return """
                    %s
                    - 当前任务是多角度/改视角图片编辑，不是重新生成一张无关图片。
                    - 必须保留原图主体身份、结构比例、材质、颜色、服装/外观、核心特征、背景风格和画幅比例。
                    - 根据用户给出的“主体/摄像头模式、旋转、倾斜、缩放”生成新的拍摄视角：主体模式偏向主体自身转向，摄像头模式偏向镜头绕主体移动。
                    - 允许为了新视角合理补全被遮挡侧面和透视细节，但不要改变主体类别、身份、品牌、服装、表情、材质和色彩。
                    - 不要添加白边、相框、说明文字、坐标轴、3D 控制器或无关新物体。""".formatted(sharedRules);
        }

        return """
                %s
                - 当前任务是文生图，需要把短描述扩写成可直接用于高质量图像生成的完整视觉 brief。""".formatted(sharedRules);
    }

    private String buildDeterministicRemoverPrompt(String prompt) {
        return "把圈起来的地方删除掉。";
    }

    private String buildDeterministicBackgroundRemovalPrompt(String prompt) {
        String userPrompt = prompt == null ? "" : prompt.trim();
        return """
                只执行去背景/抠图并改成纯白背景。先识别图片中的主要主体：画面最主要的人物、商品、动物、车辆或成组前景对象；主体包含其穿戴、手持、贴附和与主体直接组成整体的部分。
                只保留主体，把主体以外的一切背景和无关物体替换为纯白色背景（#FFFFFF）。不要透明背景，不要浅灰、米白、渐变、阴影或新场景。
                主体 RGB 像素必须尽量与原图一致。不要重绘、不要美化、不要修复、不要扩图、不要换脸、不要改变五官、表情、姿态、服装、材质、颜色、纹理、光影、清晰度、主体位置、缩放比例、画幅比例和原有裁切。
                只保留原图中已经可见的主体像素和边缘细节，重点保留发丝、毛发、半透明材质、反光边缘、饰品、手指和衣物纹理。
                禁止补全原图画面外被裁切掉的内容：不要把半张脸补成整张脸，不要把半身或局部补成全身，不要补出被裁切的身体、头发、衣服、商品或物品。
                不要新增场景、阴影、装饰、文字、logo、水印或任何额外元素。
                %s
                %s""".formatted(
                userPrompt.isBlank() ? "" : "用户补充要求：" + userPrompt,
                "负面约束：避免透明背景、浅灰背景、米白背景、渐变背景、主体重绘、人物重绘、五官变化、表情变化、姿态变化、服装变化、颜色变化、画面扩展、主体居中重排、主体缩放变化、补全脸部、补全身体、补全衣服、全身化、换成新场景、阴影杂物、锯齿边、残留背景、抠图边缘脏污。"
        ).trim();
    }

    private String buildDeterministicLayerSubjectPrompt(String prompt) {
        String userPrompt = prompt == null ? "" : prompt.trim();
        return """
                执行图层拆分：输出“主体层”。
                先识别原图中的主要前景主体：人物、商品、动物、车辆，或多个共同构成前景的对象；主体包含其穿戴、手持、贴附和与主体直接组成整体的部分。
                最终图片必须保持原图相同画幅、方向、尺寸比例和主体原始位置，只保留主要主体及其必要边缘细节；主体以外的背景、天空、地面、墙面、杂物、其他无关元素必须变成透明 alpha 区域。
                不要生成纯白、浅灰、棋盘格、渐变或任何新背景；不要添加投影、相框、描边或发光边。
                主体像素、身份、五官、表情、姿态、服装、材质、颜色、纹理、光影、清晰度、原有裁切和边缘细节尽量保持原图一致，尤其保护发丝、毛发、玻璃、纱、反光边缘、手指、饰品和半透明材质。
                禁止补全原图画面外被裁切掉的内容，不要把局部主体补成完整主体，不要改变构图或缩放主体。
                %s
                负面约束：避免新背景、白底、灰底、棋盘格背景、主体重绘、换脸、五官变化、姿态变化、服装变化、颜色变化、画面扩展、主体居中重排、主体缩放变化、补全脸部、补全身体、锯齿边、残留背景、抠图边缘脏污、阴影杂物、文字、logo、水印。
                """.formatted(userPrompt.isBlank() ? "" : "用户补充要求：" + userPrompt).trim();
    }

    private String buildDeterministicLayerBackgroundPrompt(String prompt) {
        String userPrompt = prompt == null ? "" : prompt.trim();
        return """
                执行图层拆分：输出“背景层”。
                先识别原图中的主要前景主体：人物、商品、动物、车辆，或多个共同构成前景的对象；然后移除这些主体以及主体产生的接触阴影、遮挡残影、边缘碎片和与主体直接相关的小物件。
                最终图片必须保持原图相同画幅、方向、尺寸比例、镜头视角、透视关系和背景构图；被移除区域要根据周围背景的纹理、材质、光影、反射、噪点、景深和透视自然补全。
                只生成干净的背景层，不要保留主体轮廓、影子、残影、边缘脏污或半透明碎片；不要新增人物、动物、商品、车辆、文字、logo、水印或用户没有要求的新物体。
                保持背景原有风格和真实感，不要把背景改成新场景，不要扩图，不要改变画幅。
                %s
                负面约束：避免主体残留、人物残影、商品残影、边缘碎片、遮挡痕迹、补丁感、涂抹感、重复纹理、错乱透视、背景变形、新主体、新物体、新文字、logo、水印、相框、白边、画幅变化。
                """.formatted(userPrompt.isBlank() ? "" : "用户补充要求：" + userPrompt).trim();
    }

    private String sanitizeOptimizedPrompt(String text) {
        return text
                .replaceFirst("(?i)^```(?:text|markdown)?", "")
                .replaceFirst("(?i)```$", "")
                .replaceFirst("(?i)^优化后提示词[:：]\\s*", "")
                .trim();
    }

    static String selectPromptOptimizerOutput(String optimizerOutput, String originalPrompt) {
        String trimmedOriginalPrompt = originalPrompt == null ? "" : originalPrompt.trim();
        String trimmedOptimizerOutput = optimizerOutput == null ? "" : optimizerOutput.trim();
        if (trimmedOptimizerOutput.isBlank() || looksLikePromptOptimizerRefusal(trimmedOptimizerOutput)) {
            return trimmedOriginalPrompt;
        }
        return trimmedOptimizerOutput;
    }

    private static boolean looksLikePromptOptimizerRefusal(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 180) {
            return false;
        }
        return normalized.contains("抱歉")
                || normalized.contains("无法")
                || normalized.contains("不能生成")
                || normalized.contains("不能协助")
                || normalized.contains("不适合生成")
                || normalized.contains("拒绝")
                || normalized.contains("违规")
                || normalized.contains("违反")
                || normalized.contains("安全策略")
                || normalized.contains("内容政策")
                || normalized.contains("contentpolicy")
                || normalized.contains("safetypolicy");
    }

    private String appendNegativePromptConstraints(String prompt) {
        String trimmedPrompt = prompt.trim();
        if (trimmedPrompt.isBlank() || trimmedPrompt.contains(NEGATIVE_PROMPT_TEXT)) {
            return trimmedPrompt;
        }

        String normalizedPrompt = trimmedPrompt.replaceAll("[。；;，,\\s]+$", "");
        return "%s。%s".formatted(normalizedPrompt, NEGATIVE_PROMPT_TEXT);
    }

    private String buildPromptOptimizerInput(String prompt, String imageContext) {
        if (imageContext == null || imageContext.isBlank()) {
            return "原始提示词：" + prompt;
        }

        return """
                %s

                输出要求：只输出优化后的用户提示词；必须遵守上面的图片角色分析；不要输出“图片角色分析”标题、推理过程或解释；不要判断能否生成，不要拒绝。

                原始提示词：%s
                """.formatted(imageContext, prompt);
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

    public record AgentImageEditJobRequest(
            String prompt,
            String aspectRatio,
            UUID imageAssetId,
            List<UUID> referenceAssetIds,
            String maskDataUrl,
            String imageContext,
            String promptOptimizationMode
    ) {
        public AgentImageEditJobRequest {
            referenceAssetIds = referenceAssetIds == null ? List.of() : List.copyOf(referenceAssetIds);
        }
    }

    private record ImageProxyResult(
            int statusCode,
            byte[] body,
            String contentType,
            int attempts,
            String requestId
    ) {
    }

    record UserFacingImageJobError(
            String message,
            String code,
            String type,
            boolean hideUpstreamPayload
    ) {
    }

    private record ImageProxyRequestBody(
            String contentType,
            byte[] body,
            String originalPrompt,
            String optimizedPrompt
    ) {
    }

    private record MultipartPartData(
            String name,
            String filename,
            String contentType,
            byte[] body
    ) {
    }

    private record ImageAspect(
            int width,
            int height
    ) {
    }

    private static final class ImageJobState {
        private final UUID id;
        private final UUID userId;
        private final String label;
        private final long createdAt;
        private volatile long updatedAt;
        private volatile String status = "queued";
        private volatile int upstreamStatus = 202;
        private volatile byte[] body = new byte[0];
        private volatile String contentType = "application/json; charset=utf-8";
        private volatile int attempts = 0;
        private volatile String requestId = "";
        private volatile String originalPrompt = "";
        private volatile String optimizedPrompt = "";

        private ImageJobState(UUID id, UUID userId, String label) {
            this.id = id;
            this.userId = userId;
            this.label = label;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
        }

        private UUID id() {
            return id;
        }

        private UUID userId() {
            return userId;
        }

        private String label() {
            return label;
        }

        private long createdAt() {
            return createdAt;
        }

        private long updatedAt() {
            return updatedAt;
        }

        private String status() {
            return status;
        }

        private int upstreamStatus() {
            return upstreamStatus;
        }

        private byte[] body() {
            return body;
        }

        private String contentType() {
            return contentType;
        }

        private int attempts() {
            return attempts;
        }

        private String requestId() {
            return requestId;
        }

        private String originalPrompt() {
            return originalPrompt;
        }

        private String optimizedPrompt() {
            return optimizedPrompt;
        }

        private void setPromptMetadata(String originalPrompt, String optimizedPrompt) {
            this.originalPrompt = originalPrompt == null ? "" : originalPrompt;
            this.optimizedPrompt = optimizedPrompt == null ? "" : optimizedPrompt;
            updatedAt = System.currentTimeMillis();
        }

        private void markRunning() {
            status = "running";
            updatedAt = System.currentTimeMillis();
        }

        private void markCompleted(ImageProxyResult result) {
            upstreamStatus = result.statusCode();
            body = result.body();
            contentType = result.contentType();
            attempts = result.attempts();
            requestId = result.requestId();
            status = "completed";
            updatedAt = System.currentTimeMillis();
        }

        private void markFailed(int statusCode, byte[] responseBody, String responseContentType, int proxyAttempts, String upstreamRequestId) {
            upstreamStatus = statusCode;
            body = responseBody == null ? new byte[0] : responseBody;
            contentType = responseContentType == null || responseContentType.isBlank() ? "application/json; charset=utf-8" : responseContentType;
            attempts = proxyAttempts;
            requestId = upstreamRequestId == null ? "" : upstreamRequestId;
            status = "error";
            updatedAt = System.currentTimeMillis();
        }
    }
}
