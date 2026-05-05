package com.artisanlab.ai;

import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import com.artisanlab.externalapi.ExternalAiImageDtos;
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
import java.io.ByteArrayInputStream;
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

import javax.imageio.ImageIO;

@Service
public class AiProxyService {
    private static final Logger log = LoggerFactory.getLogger(AiProxyService.class);
    private static final int MAX_EXTERNAL_IMAGE_BYTES = 25 * 1024 * 1024;
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
    private static final String PRODUCT_POSTER_FACT_GUARD_TEXT = "关于产品本身的厚度、尺寸、规格、容量、材质级别、性能、认证、适配、工艺、数量等事实属性，只能使用用户明确提供的信息，或产品原图/包装上已经清晰可读的现有文字与标识；禁止自行脑补、补全或杜撰任何具体数值、技术参数或宣传结论。未提供就不要写进画面文案，也不要生成不存在的参数标注；如果出现人物、桌面等参照物但没有明确尺寸，只需让产品视觉比例自然可信，不要据此反推出任何未提供参数。";
    static final String VIEW_CHANGE_GAZE_LOCK_TEXT = "硬性多角度视线约束：人物、动物或角色的身体、头部和眼睛都锁定在原图里的世界坐标中，不跟随新相机转动。"
            + "如果原图角色正对原始相机，当新相机移动到左侧、右侧或斜侧时，画面应看到侧脸或三分之四脸；角色视线仍指向原始相机位置，"
            + "不应继续直视当前画面、当前观看者或新镜头。禁止 looking at viewer、looking at new camera、direct eye contact with the new camera、subject turns head toward the new camera。";
    static final String VIEW_CHANGE_FRAMING_LOCK_TEXT = "硬性多角度景别约束：保持原图镜头焦段、FOV 视野范围、主体占画面比例、裁切边界、景深和背景可见范围；"
            + "只改变相机方位和俯仰造成的透视，不改变镜头远近。禁止扩大视野，禁止露出比原图更多的背景，禁止把特写变成近景、中景或远景。";

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
        return createTextToImageJobsFromAgent(userId, prompt, aspectRatio, "2k", 1, "").get(0);
    }

    public List<Map<String, Object>> createTextToImageJobsFromAgent(
            UUID userId,
            String prompt,
            String aspectRatio,
            int count
    ) throws IOException {
        return createTextToImageJobsFromAgent(userId, prompt, aspectRatio, "2k", count, "", true);
    }

    public List<Map<String, Object>> createTextToImageJobsFromAgent(
            UUID userId,
            String prompt,
            String aspectRatio,
            String imageResolution,
            int count,
            String promptOptimizeContext
    ) throws IOException {
        return createTextToImageJobsFromAgent(userId, prompt, aspectRatio, imageResolution, count, promptOptimizeContext, true);
    }

    public List<Map<String, Object>> createTextToImageJobsFromAgent(
            UUID userId,
            String prompt,
            String aspectRatio,
            String imageResolution,
            int count,
            String promptOptimizeContext,
            boolean enablePromptOptimization
    ) throws IOException {
        cleanupImageJobs();
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        ObjectNode body = objectMapper.createObjectNode();
        String normalizedImageResolution = normalizeImageResolution(imageResolution);
        body.put("model", config.model());
        body.put("prompt", appendImageOutputInstructions(prompt, aspectRatio, normalizedImageResolution));
        if (!normalizedImageResolution.isBlank()) {
            body.put("imageResolution", normalizedImageResolution);
        }
        if (promptOptimizeContext != null && !promptOptimizeContext.isBlank()) {
            body.put("promptOptimizeContext", promptOptimizeContext.trim());
            if (enablePromptOptimization) {
                body.put("promptOptimizationMode", "skill-text-to-image");
            }
        }
        if (!enablePromptOptimization) {
            body.put("promptOptimizationMode", "disabled");
        }

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

    public Map<String, Object> createExternalTextToImageJob(
            UUID ownerId,
            ExternalAiImageDtos.TextToImageRequest request
    ) throws IOException {
        cleanupImageJobs();
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredServerDefaultConfig();
        ObjectNode body = objectMapper.createObjectNode();
        String normalizedImageResolution = normalizeImageResolution(request.imageResolution());
        body.put("model", config.model());
        body.put("prompt", appendImageOutputInstructions(request.prompt(), request.aspectRatio(), normalizedImageResolution));
        if (!normalizedImageResolution.isBlank()) {
            body.put("imageResolution", normalizedImageResolution);
        }
        body.put("promptOptimizationMode", request.promptOptimizationEnabled() ? "skill-text-to-image" : "disabled");

        ImageProxyRequestBody requestBody = readJsonImageProxyRequestBody(
                config,
                "text-to-image",
                "application/json; charset=utf-8",
                objectMapper.writeValueAsBytes(body)
        );
        return enqueueImageJob(ownerId, "text-to-image", "images/generations", config, "application/json", requestBody);
    }

    public Map<String, Object> createExternalImageEditJob(
            UUID ownerId,
            ExternalAiImageDtos.ImageToImageRequest request
    ) throws IOException {
        cleanupImageJobs();
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredServerDefaultConfig();
        ImageProxyRequestBody requestBody = buildExternalImageEditRequestBody(config, request);
        return enqueueImageJob(ownerId, "image-to-image", "images/edits", config, "application/json", requestBody);
    }

    public Map<String, Object> getExternalImageJobSnapshot(UUID ownerId, String jobId) {
        return getImageJobSnapshot(ownerId, jobId);
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
        String normalizedImageResolution = normalizeImageResolution(request.imageResolution());
        String prompt = appendImageOutputInstructions(request.prompt(), request.aspectRatio(), normalizedImageResolution);
        String promptOptimizationMode = normalizePromptOptimizationMode(request.promptOptimizationMode(), "image-to-image");
        String optimizedPrompt = shouldOptimizePrompt(promptOptimizationMode)
                ? optimizePromptInline(config, promptOptimizationMode, prompt, request.imageContext())
                : "";
        ImageOutputSettings outputSettings = resolveImageOutputSettings(prompt, request.aspectRatio(), normalizedImageResolution, false);

        writeTextMultipartPart(output, boundary, "model", config.model());
        writeTextMultipartPart(output, boundary, "prompt", optimizedPrompt.isBlank() ? prompt : optimizedPrompt);
        if (!outputSettings.size().isBlank()) {
            writeTextMultipartPart(output, boundary, "size", outputSettings.size());
        }
        if (outputSettings.highQuality()) {
            writeTextMultipartPart(output, boundary, "quality", "high");
        }
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

    private ImageProxyRequestBody buildExternalImageEditRequestBody(
            UserApiConfigDtos.ResolvedConfig config,
            ExternalAiImageDtos.ImageToImageRequest request
    ) throws IOException {
        String boundary = "----LivartProxyBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String normalizedImageResolution = normalizeImageResolution(request.imageResolution());
        String prompt = appendImageOutputInstructions(request.prompt(), request.aspectRatio(), normalizedImageResolution);
        String promptOptimizationMode = request.promptOptimizationEnabled() ? "skill-image-to-image" : "disabled";
        String optimizedPrompt = shouldOptimizePrompt(promptOptimizationMode)
                ? optimizePromptInline(config, promptOptimizationMode, prompt, "")
                : "";
        ImageOutputSettings outputSettings = resolveImageOutputSettings(prompt, request.aspectRatio(), normalizedImageResolution, false);

        writeTextMultipartPart(output, boundary, "model", config.model());
        writeTextMultipartPart(output, boundary, "prompt", optimizedPrompt.isBlank() ? prompt : optimizedPrompt);
        if (!outputSettings.size().isBlank()) {
            writeTextMultipartPart(output, boundary, "size", outputSettings.size());
        }
        if (outputSettings.highQuality()) {
            writeTextMultipartPart(output, boundary, "quality", "high");
        }
        writeExternalImagePart(output, boundary, "image", request.imageBase64(), "image.png");
        for (int index = 0; index < request.referenceImages().size(); index += 1) {
            writeExternalImagePart(output, boundary, "image", request.referenceImages().get(index), "reference-%d.png".formatted(index + 1));
        }
        if (request.maskBase64() != null && !request.maskBase64().isBlank()) {
            writeExternalImagePart(output, boundary, "mask", request.maskBase64(), "mask.png");
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
        String imageResolution = normalizeImageResolution(readTextField(rewrittenBody, "imageResolution"));
        String imageContext = firstNonBlank(
                readTextField(rewrittenBody, "imageContext"),
                readTextField(rewrittenBody, "promptOptimizeContext"),
                readTextField(rewrittenBody, "promptContext")
        );
        String promptOptimizationMode = normalizePromptOptimizationMode(
                readTextField(rewrittenBody, "promptOptimizationMode"),
                label
        );
        rewrittenBody.remove(List.of("imageContext", "promptOptimizeContext", "promptContext", "promptOptimizationMode", "imageResolution"));

        String optimizedPrompt = shouldOptimizePrompt(promptOptimizationMode)
                ? optimizePromptInline(config, promptOptimizationMode, prompt, imageContext)
                : "";
        if (!optimizedPrompt.isBlank()) {
            rewrittenBody.put("prompt", optimizedPrompt);
        }
        applyDefaultTextToImageSize(label, rewrittenBody, prompt, imageResolution);

        return new ImageProxyRequestBody(contentType, objectMapper.writeValueAsBytes(rewrittenBody), prompt, optimizedPrompt);
    }

    private void applyDefaultTextToImageSize(String label, ObjectNode body, String prompt, String imageResolution) {
        if (!"text-to-image".equals(label) || !defaultImageSizeEnabled || hasUsableField(body, "size")) {
            return;
        }

        String model = readTextField(body, "model");
        if (!supportsDefaultImageSize(model)) {
            return;
        }

        ImageOutputSettings outputSettings = imageResolution.isBlank()
                ? new ImageOutputSettings(resolveDefaultTextToImageSize(prompt, defaultImageLongSide), defaultImageLongSide >= 2048)
                : resolveImageOutputSettings(prompt, readTextField(body, "aspectRatio"), imageResolution, true);
        if (!outputSettings.size().isBlank()) {
            body.put("size", outputSettings.size());
        }
        if (outputSettings.highQuality() && !hasUsableField(body, "quality")) {
            body.put("quality", "high");
        }
    }

    private String appendImageOutputInstructions(String prompt, String aspectRatio, String imageResolution) {
        String instruction = joinNonBlankLines(
                aspectRatioToPromptInstruction(aspectRatio),
                imageResolutionToPromptInstruction(imageResolution)
        );
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if (instruction.isBlank()) {
            return trimmedPrompt;
        }
        boolean hasAspectInstruction = trimmedPrompt.contains("画幅比例要求");
        boolean hasResolutionInstruction = trimmedPrompt.contains("清晰度要求") || trimmedPrompt.contains("输出分辨率要求");
        if (hasAspectInstruction && hasResolutionInstruction) return trimmedPrompt;
        if (hasAspectInstruction) instruction = imageResolutionToPromptInstruction(imageResolution);
        if (hasResolutionInstruction) instruction = aspectRatioToPromptInstruction(aspectRatio);
        if (instruction.isBlank()) return trimmedPrompt;
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
            case "2:1" -> "画幅比例要求：最终输出为 2:1 横向完整球形全景 equirectangular 构图，也就是 360° 水平视角 + 180° 垂直视角；左右边缘自然衔接，顶部和底部空间合理连续，不要添加白边、相框或多余留白来凑比例。";
            default -> "";
        };
    }

    private String imageResolutionToPromptInstruction(String imageResolution) {
        return switch (normalizeImageResolution(imageResolution)) {
            case "1k" -> "清晰度要求：目标为 1K 清晰度，画面清楚干净，不要添加白边或相框。";
            case "2k" -> "清晰度要求：目标为 2K 高清输出，高细节、高质量、画面清晰，不要添加白边或相框。";
            case "4k" -> "清晰度要求：目标为 4K 超高清输出，高分辨率、高细节、高质量，适合放大查看，不要添加白边或相框。";
            default -> "";
        };
    }

    private String normalizeImageResolution(String imageResolution) {
        return switch (imageResolution == null ? "" : imageResolution.trim().toLowerCase(Locale.ROOT)) {
            case "1k", "2k", "4k" -> imageResolution.trim().toLowerCase(Locale.ROOT);
            default -> "";
        };
    }

    private String joinNonBlankLines(String... lines) {
        return String.join("\n", java.util.Arrays.stream(lines)
                .filter(line -> line != null && !line.isBlank())
                .toList());
    }

    private boolean hasUsableField(ObjectNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        return value != null && !value.isNull() && (!value.isTextual() || !value.asText().isBlank());
    }

    private boolean supportsDefaultImageSize(String model) {
        return "gpt-image-2".equalsIgnoreCase(model == null ? "" : model.trim());
    }

    private ImageOutputSettings resolveImageOutputSettings(
            String prompt,
            String aspectRatio,
            String imageResolution,
            boolean allowSquareFallback
    ) {
        String normalizedResolution = normalizeImageResolution(imageResolution);
        if (normalizedResolution.isBlank()) {
            return new ImageOutputSettings("", false);
        }

        Optional<ImageAspect> requestedAspect = parseAspectRatio(aspectRatio).or(() -> detectPromptAspectRatio(prompt));
        if (requestedAspect.isEmpty() && !allowSquareFallback) {
            return new ImageOutputSettings("", shouldUseHighQuality(normalizedResolution));
        }

        ImageAspect aspect = requestedAspect.orElse(new ImageAspect(1, 1));
        return new ImageOutputSettings(resolveImageSizeForTier(aspect, normalizedResolution), shouldUseHighQuality(normalizedResolution));
    }

    private boolean shouldUseHighQuality(String imageResolution) {
        String normalizedResolution = normalizeImageResolution(imageResolution);
        return "2k".equals(normalizedResolution) || "4k".equals(normalizedResolution);
    }

    private String resolveImageSizeForTier(ImageAspect aspect, String imageResolution) {
        String aspectKey = "%d:%d".formatted(aspect.width(), aspect.height());
        return switch (normalizeImageResolution(imageResolution) + "|" + aspectKey) {
            case "1k|1:1" -> "1024x1024";
            case "1k|4:3" -> "1536x1152";
            case "1k|3:4" -> "1152x1536";
            case "1k|16:9" -> "1536x864";
            case "1k|9:16" -> "864x1536";
            case "1k|2:1" -> "1536x768";
            case "2k|1:1" -> "2048x2048";
            case "2k|4:3" -> "2048x1536";
            case "2k|3:4" -> "1536x2048";
            case "2k|16:9" -> "2048x1152";
            case "2k|9:16" -> "1152x2048";
            case "2k|2:1" -> "2048x1024";
            case "4k|1:1" -> "2880x2880";
            case "4k|4:3" -> "3264x2448";
            case "4k|3:4" -> "2448x3264";
            case "4k|16:9" -> "3840x2160";
            case "4k|9:16" -> "2160x3840";
            case "4k|2:1" -> "3840x1920";
            default -> "";
        };
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

    private static Optional<ImageAspect> parseAspectRatio(String aspectRatio) {
        return switch (aspectRatio == null ? "" : aspectRatio.trim()) {
            case "1:1" -> Optional.of(new ImageAspect(1, 1));
            case "4:3" -> Optional.of(new ImageAspect(4, 3));
            case "3:4" -> Optional.of(new ImageAspect(3, 4));
            case "16:9" -> Optional.of(new ImageAspect(16, 9));
            case "9:16" -> Optional.of(new ImageAspect(9, 16));
            case "2:1" -> Optional.of(new ImageAspect(2, 1));
            default -> Optional.empty();
        };
    }

    private static Optional<ImageAspect> detectPromptAspectRatio(String prompt) {
        String text = prompt == null ? "" : prompt.replace('：', ':').replaceAll("\\s+", "");
        if (containsAspectRatio(text, 2, 1) || text.contains("360°") || text.contains("360度") || text.toLowerCase(Locale.ROOT).contains("equirectangular")) {
            return Optional.of(new ImageAspect(2, 1));
        }
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
        if ("disabled".equals(normalizedValue)
                || "image-remover".equals(normalizedValue)
                || "background-removal".equals(normalizedValue)
                || "layer-split-subject".equals(normalizedValue)
                || "layer-split-background".equals(normalizedValue)
                || "view-change".equals(normalizedValue)
                || "panorama".equals(normalizedValue)
                || "product-poster".equals(normalizedValue)
                || "skill-text-to-image".equals(normalizedValue)
                || "skill-image-to-image".equals(normalizedValue)) {
            return normalizedValue;
        }
        return fallbackMode;
    }

    static boolean shouldOptimizePrompt(String mode) {
        return !"disabled".equalsIgnoreCase(mode == null ? "" : mode.trim());
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

    private void writeExternalImagePart(
            ByteArrayOutputStream output,
            String boundary,
            String name,
            String imageValue,
            String fallbackFilename
    ) throws IOException {
        writeMultipartPart(output, boundary, externalImageMultipartPart(name, imageValue, fallbackFilename));
    }

    private MultipartPartData externalImageMultipartPart(String name, String imageValue, String fallbackFilename) {
        DecodedExternalImage decodedImage = decodeExternalImage(imageValue, fallbackFilename);
        AssetService.PreparedImageContent preparedImage = assetService.prepareModelInputImage(decodedImage.bytes(), decodedImage.contentType());
        String filename = modelInputFilename(null, decodedImage.filename(), preparedImage.contentType());
        return new MultipartPartData(name, filename, preparedImage.contentType(), preparedImage.bytes());
    }

    private DecodedExternalImage decodeExternalImage(String value, String fallbackFilename) {
        String trimmedValue = value == null ? "" : value.trim();
        if (trimmedValue.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_IMAGE_REQUIRED", "图生图原图不能为空");
        }

        String contentType = "image/png";
        String encoded = trimmedValue;
        if (trimmedValue.startsWith("data:")) {
            int commaIndex = trimmedValue.indexOf(',');
            int semicolonIndex = trimmedValue.indexOf(';');
            if (commaIndex <= 0 || semicolonIndex <= 5 || !trimmedValue.substring(semicolonIndex + 1, commaIndex).contains("base64")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_IMAGE_DATA", "图片数据格式无效");
            }
            contentType = trimmedValue.substring("data:".length(), semicolonIndex).trim().toLowerCase(Locale.ROOT);
            encoded = trimmedValue.substring(commaIndex + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_IMAGE_DATA", "图片 Base64 数据无效");
        }
        if (bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_IMAGE_DATA", "图片内容为空");
        }
        if (bytes.length > MAX_EXTERNAL_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "EXTERNAL_IMAGE_TOO_LARGE", "图片超过 25MB，无法处理");
        }
        ensureImageBytesReadable(bytes);

        String filename = fallbackFilename == null || fallbackFilename.isBlank()
                ? "image" + imageExtensionForContentType(contentType)
                : fallbackFilename;
        return new DecodedExternalImage(bytes, contentType, filename);
    }

    private void ensureImageBytesReadable(byte[] bytes) {
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_IMAGE_DATA", "图片内容无法识别");
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_IMAGE_DATA", "图片内容无法识别");
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
            String optimizedPrompt = finalizeOptimizedPrompt(
                    mode,
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
            return finalizeOptimizedPrompt(mode, trimmedPrompt);
        } catch (RuntimeException exception) {
            log.warn(
                    "[prompt-optimizer] inline skipped mode={} duration={}ms error={}",
                    mode,
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            return finalizeOptimizedPrompt(mode, trimmedPrompt);
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
            JsonNode quality = data.get("quality");
            JsonNode prompt = data.get("prompt");

            if (model != null && model.isTextual()) {
                summary.put("model", model.asText());
            }
            if (size != null && size.isTextual()) {
                summary.put("size", size.asText());
            }
            if (quality != null && quality.isTextual()) {
                summary.put("quality", quality.asText());
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
        if (isLikelyUpstreamTransportFailure(upstreamStatus, upstreamMessage, upstreamCode, upstreamType)) {
            return new UserFacingImageJobError(
                    "图片生成失败：上游 AI 生成连接中断，可能是生成耗时过长或网关超时，请稍后重试；如果多次出现，可以先降低画幅/分辨率再试。",
                    "UPSTREAM_CONNECTION_INTERRUPTED",
                    "upstream_network",
                    false
            );
        }

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

    private static boolean isLikelyUpstreamTransportFailure(
            int upstreamStatus,
            String upstreamMessage,
            String upstreamCode,
            String upstreamType
    ) {
        String combined = normalizeErrorText(upstreamMessage, upstreamCode, upstreamType);
        if (combined.isBlank()) return false;

        return upstreamStatus == 502 && (
                combined.contains("eof")
                        || combined.contains("connection reset")
                        || combined.contains("connection closed")
                        || combined.contains("connection aborted")
                        || combined.contains("read timed out")
                        || combined.contains("timeout")
                        || combined.contains("timed out")
                        || combined.contains("socket")
                        || combined.contains("stream closed")
                        || combined.contains("premature")
                        || combined.contains("网关超时")
                        || combined.contains("连接中断")
                        || combined.contains("连接断开")
        );
    }

    private static boolean isLikelyContentPolicyBlocked(
            int upstreamStatus,
            String upstreamMessage,
            String upstreamCode,
            String upstreamType
    ) {
        String combined = normalizeErrorText(upstreamMessage, upstreamCode, upstreamType);

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

    private static String normalizeErrorText(String upstreamMessage, String upstreamCode, String upstreamType) {
        return String.join(
                " ",
                upstreamMessage == null ? "" : upstreamMessage,
                upstreamCode == null ? "" : upstreamCode,
                upstreamType == null ? "" : upstreamType
        ).toLowerCase(Locale.ROOT);
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
        String productPosterSharedRules = sharedRules.replace(
                "- 保留用户原始意图，不新增用户没有要求的主体或文字。",
                "- 保留用户原始意图，不新增用户没有要求的主体；商品详情图任务允许为了电商详情表达补充必要的中文短标题、短卖点和标签。"
        ).replace(
                "- 只补充画面主体、场景、构图、风格、材质、色彩、光影、镜头/视角和质量描述；最终是否能生成由后续生图接口判断。",
                "- 只补充画面主体、场景、构图、风格、材质、色彩、光影、镜头/视角和质量描述；最终是否能生成由后续生图接口判断。\n- %s".formatted(PRODUCT_POSTER_FACT_GUARD_TEXT)
        );

        if ("skill-text-to-image".equals(mode)) {
            return """
                    你是外部 Skill 的提示词编译器。你的任务不是使用 livart 默认通用优化模板，而是严格根据上下文中的“外部 Skill / Skill 指南”把用户输入整理成可直接提交给图片生成模型的最终提示词。
                    只输出最终提示词，不要解释，不要 Markdown，不要加标题，不要拒绝，不要判断能否生成。
                    要求：
                    - 优先遵守外部 Skill 指南；如果 Skill 指南和默认审美描述冲突，以 Skill 指南为准。
                    - 保留用户原始目标、主体、数量、画幅、文字内容、风格和约束，不要新增用户没有要求的主体或文字。
                    - 可以按 Skill 指南补充构图、镜头、材质、光影、质量、文字排版和交付质量描述。
                    - 不要输出内部图片 ID、系统字段、Skill 文件路径或脚本调用方式。
                    - 不要尝试执行 Skill 脚本；只根据 Skill 文档编译提示词。
                    - 使用中文输出一段完整提示词。""";
        }

        if ("skill-image-to-image".equals(mode)) {
            return """
                    你是外部 Skill 的图片编辑提示词编译器。你的任务不是使用 livart 默认通用图生图模板，而是严格根据上下文中的“外部 Skill / Skill 指南”和图片角色分析，把用户输入整理成可直接提交给图片编辑模型的最终提示词。
                    只输出最终提示词，不要解释，不要 Markdown，不要加标题，不要拒绝，不要判断能否生成。
                    要求：
                    - 优先遵守外部 Skill 指南；如果 Skill 指南和默认审美描述冲突，以 Skill 指南为准。
                    - 必须理解原图/编辑目标/参考图/素材图之间的关系，不要把“图片角色分析”标题和内部说明原样输出。
                    - 原文中任何 @xxx 图片引用标记都是系统占位符；必须改写为“原图”“参考图 1”等角色名称，不要输出内部 ID。
                    - 保留原图未要求修改的主体身份、结构比例、画幅、构图、透视、材质、光影和关键细节。
                    - 如果有蒙版、圈选、涂抹或局部区域，必须明确只修改该区域，其余区域保持不变。
                    - 不要尝试执行 Skill 脚本；只根据 Skill 文档编译提示词。
                    - 使用中文输出一段完整提示词。""";
        }

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
                    - 必须把原图当作完整、固定不动的三维场景处理；相机/观察点围绕整张画面移动，而不是只围绕人物、商品、车辆或单个物体改角度。
                    - 根据用户给出的“整图视角/观察视角、旋转、倾斜、缩放”生成新的拍摄视角：画面中所有可见元素，包括人物、动物、商品、车辆、家具、道具、背景、地面/室内结构、建筑、光源、阴影和反射，都要按照同一个新相机位姿产生一致透视变化。
                    - 如果用户要求左侧视角或方位角为负，结果必须显示人物、车辆、家具、建筑、地面结构和所有可见物体的左侧面；如果要求右侧视角或方位角为正，结果必须显示所有可见物体的右侧面。不要做左右镜像翻转。
                    - 人物、动物或角色必须保持原先的身体姿态、头部朝向、表情、动作和眼神方向；不要让角色重新转头、转身或看向新镜头。即使原图角色正对原始镜头，新视角也只是从侧面观察这个固定姿态，不能让角色追随新相机。
                    - %s
                    - %s
                    - 必须保留原图完整内容、各主要元素身份、结构比例、材质、颜色、服装/外观、核心特征、背景风格、相对位置关系、画幅比例和原始景别。
                    - 允许为了整图新视角合理补全被遮挡侧面和透视细节，但不要只旋转某个元素、不要让背景/地面/桌面/车门/车轮/墙面停留在原视角，也不要改变画面元素类别、人物身份、品牌、服装、表情、材质和色彩。
                    - 不要添加白边、相框、说明文字、坐标轴、3D 控制器或无关新物体。""".formatted(sharedRules, VIEW_CHANGE_GAZE_LOCK_TEXT, VIEW_CHANGE_FRAMING_LOCK_TEXT);
        }

        if ("panorama".equals(mode)) {
            return """
                    %s
                    - 当前任务是把原图转换为完整球形全景 / equirectangular panorama，不是重新生成无关场景，不是普通扩图，不是改视角。
                    - 输出必须是 2:1 横向完整球形全景构图，也就是 360° 水平视角 + 180° 垂直视角，可以用于 360° 全景查看器；左右边缘必须自然衔接，顶部和底部空间要合理连续，水平线稳定，画面不能出现白边、黑边、相框或拼贴痕迹。
                    - 必须保持原图场景主题、主体身份、物体种类、相对位置关系、材质、颜色、光影方向、镜头氛围和核心视觉记忆不变。
                    - 只允许补全原图视野外为了形成环绕空间所需的合理延展区域，例如左右两侧环境、天空/天花板、地面/桌面、墙面、道路、室内结构、背景空间和遮挡后续区域。
                    - 原图已有的人物、动物、商品、车辆、家具、建筑、文字/logo 和关键物体不能被替换成新物体，不能改变身份、结构比例、颜色、材质、姿态或核心外观。
                    - 如果需要补充新区域，新区域必须从原图透视、材质、光源和空间规律自然延展；不要新增与原图无关的主体、人物、品牌、文字、水印、二维码或装饰。
                    - 使用中文输出一段可直接用于图片编辑接口的完整提示词。""".formatted(sharedRules);
        }

        if ("product-poster".equals(mode)) {
            return """
                    %s
                    - 当前任务是基于产品原图生成电商商品详情图，不是重新设计一个新商品。
                    - 必须遵守输入上下文中的产品图模式：单品模式时第 1 张 image 是主产品，其余 image 是角度/细节/包装参考；系列模式时所有 image 是同一产品系列的不同产品/SKU/款式/颜色。
                    - 单品模式要保持第 1 张产品的形状、颜色、材质、纹理、logo、图案、边缘、结构比例和核心识别点尽量不变；系列模式要分别保留每个产品的独立外观和差异，不要融合成一个商品，也不要只突出其中一个商品。
                    - 风格目标必须明显偏向“更有艺术感、更简洁、更优雅”：画面安静、克制、高级，像高端品牌画册或精品杂志内页，而不是普通促销海报。
                    - 一张详情图只讲一个重点：首屏吸引力、材质工艺、场景应用、尺寸参数、人群价值或包装送礼其一；不要把所有信息塞进同一张图。
                    - 构图必须明显采用杂志排版 / editorial magazine layout：优先使用杂志式网格、标题区、标签区、留白区和轻微不对称的编辑感版式；产品是绝对主角，信息层级清晰，元素少而准，道具少而精，不要为了填满画面堆砌背景或装饰。
                    - 配色尽量控制在 2 到 3 个主色，优先低饱和、中性色或符合行业气质的克制点缀色；不要使用廉价炫彩渐变、大面积高饱和撞色或脏乱配色。
                    - 优先强调光影、材质、反射、纹理、颗粒、纸感和空间呼吸感；质感表达比装饰数量更重要。
                    - 必须先判断产品所在行业，再选择匹配行业的视觉风格和排版密度，不能所有产品都套同一种电商模板：
                      * 香水、香氛、珠宝、艺术礼品：更有艺术气息，更简洁，更多高级留白，光影和材质更精致，文字更少更克制。
                      * 美妆、护肤：干净通透，柔和高光，强调成分、功效、肤感和质地。
                      * 数码、AI、潮流贴纸：科技感、年轻化、桌搭场景，色块和图标更明确。
                      * 食品饮品：温暖、有食欲、真实场景，强调口味、原料、产地和新鲜感。
                      * 家居日用：生活方式、空间感、清爽可信，强调尺寸、材质和使用场景。
                      * 服饰鞋包：穿搭、模特、材质细节，强调版型、面料和搭配。
                    - 可以根据产品信息和行业特点补充商业背景、真实使用场景、道具、光影、空间层次、信息卡片、图标、分割线、标题区域、卖点区域和详情页模块。
                    - 如果输入上下文中包含“外部 Skill / Skill 指南”，必须把它作为商品详情图审美、视觉语言、构图层级和提示词结构约束使用。
                    - 如果用户填写了材质、颜色、款式、场景、人群或卖点，必须体现在商品详情图的文字描述和视觉策略中。
                    - %s
                    - 画面必须包含清晰可读的中文短文字：短标题、1 到 3 个核心卖点（普通品类可到 4 个）、材质/规格/适用场景标签；文字要短句化、模块化、排版整齐，像杂志栏位里的品牌文案，不像大促销海报。
                    - 不要把产品改成其他款式，不要生成长段落、乱码、水印、二维码、无关品牌、虚构价格或与产品冲突的装饰。
                    - 输出应像可直接上架的电商商品详情图、详情页首屏、卖点说明图、材质工艺图或场景说明图。""".formatted(productPosterSharedRules, PRODUCT_POSTER_FACT_GUARD_TEXT);
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

    private String finalizeOptimizedPrompt(String mode, String prompt) {
        String guardedPrompt = "view-change".equals(mode)
                ? appendViewChangeGazeConstraints(prompt)
                : "panorama".equals(mode) ? appendPanoramaConstraints(prompt) : prompt;
        return appendNegativePromptConstraints(guardedPrompt == null ? "" : guardedPrompt);
    }

    static String appendPanoramaConstraints(String prompt) {
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if (trimmedPrompt.isBlank()) {
            return trimmedPrompt;
        }

        String normalizedPrompt = trimmedPrompt.replaceAll("[。；;，,\\s]+$", "");
        List<String> constraints = new ArrayList<>();
        if (!trimmedPrompt.contains("2:1")) {
            constraints.add("硬性全景画幅约束：最终输出必须是 2:1 横向完整球形全景 equirectangular panorama，也就是 360° 水平视角 + 180° 垂直视角。");
        }
        if (!trimmedPrompt.contains("左右边缘")) {
            constraints.add("硬性全景拼接约束：左右边缘必须自然衔接，顶部和底部空间合理连续，水平线稳定，不能有白边、黑边、相框或拼贴痕迹。");
        }
        if (!trimmedPrompt.contains("保持原图场景")) {
            constraints.add("硬性一致性约束：保持原图场景主题、主体身份、物体种类、相对位置、材质、颜色、光影方向和整体氛围不变，只补全形成 360° 环绕空间所需的合理延展区域。");
        }
        if (constraints.isEmpty()) {
            return trimmedPrompt;
        }
        return "%s。\n%s".formatted(normalizedPrompt, String.join("\n", constraints));
    }

    static String appendViewChangeGazeConstraints(String prompt) {
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if (trimmedPrompt.isBlank()) {
            return trimmedPrompt;
        }

        String normalizedPrompt = trimmedPrompt.replaceAll("[。；;，,\\s]+$", "");
        List<String> constraints = new ArrayList<>();
        if (!trimmedPrompt.contains("direct eye contact with the new camera")) {
            constraints.add(VIEW_CHANGE_GAZE_LOCK_TEXT);
        }
        if (!trimmedPrompt.contains("禁止扩大视野")) {
            constraints.add(VIEW_CHANGE_FRAMING_LOCK_TEXT);
        }
        if (constraints.isEmpty()) {
            return trimmedPrompt;
        }
        return "%s。\n%s".formatted(normalizedPrompt, String.join("\n", constraints));
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
            String promptOptimizationMode,
            String imageResolution
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

    private record DecodedExternalImage(
            byte[] bytes,
            String contentType,
            String filename
    ) {
    }

    private record ImageOutputSettings(
            String size,
            boolean highQuality
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
