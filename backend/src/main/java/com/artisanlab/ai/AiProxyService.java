package com.artisanlab.ai;

import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String ORIGINAL_PROMPT_HEADER = "X-Livart-Original-Prompt-B64";
    private static final String OPTIMIZED_PROMPT_HEADER = "X-Livart-Optimized-Prompt-B64";
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
    private final HttpClient httpClient;
    private final Map<UUID, ImageJobState> imageJobs = new ConcurrentHashMap<>();
    private final ExecutorService imageJobExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    public AiProxyService(
            UserApiConfigService userApiConfigService,
            AssetService assetService,
            ObjectMapper objectMapper,
            ImageJobEventBroadcaster imageJobEventBroadcaster
    ) {
        this.userApiConfigService = userApiConfigService;
        this.assetService = assetService;
        this.objectMapper = objectMapper;
        this.imageJobEventBroadcaster = imageJobEventBroadcaster;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PreDestroy
    public void shutdown() {
        imageJobExecutor.shutdownNow();
    }

    public ResponseEntity<byte[]> proxyImageRequest(
            UUID userId,
            String label,
            String path,
            HttpServletRequest request
    ) throws IOException {
        UserApiConfigDtos.Response config = userApiConfigService.getRequiredConfig(userId);
        ImageProxyRequestBody requestBody = readImageProxyRequestBody(userId, request, config, label);
        ImageProxyResult result = executeImageRequest(
                label,
                joinUrl(config.baseUrl(), path),
                config.apiKey(),
                request.getHeader(HttpHeaders.ACCEPT),
                requestBody.contentType(),
                requestBody.body()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, result.contentType());
        headers.add(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.add("X-Proxy-Attempts", String.valueOf(result.attempts()));
        addPromptMetadataHeaders(headers, requestBody.originalPrompt(), requestBody.optimizedPrompt());
        if (!result.requestId().isBlank()) {
            headers.add("X-Upstream-Request-Id", result.requestId());
        }

        return ResponseEntity.status(result.statusCode()).headers(headers).body(result.body());
    }

    public ResponseEntity<Map<String, Object>> createImageJob(
            UUID userId,
            String label,
            String path,
            HttpServletRequest request
    ) throws IOException {
        cleanupImageJobs();
        UserApiConfigDtos.Response config = userApiConfigService.getRequiredConfig(userId);
        UUID jobId = UUID.randomUUID();
        ImageJobState job = new ImageJobState(jobId, userId, label);
        imageJobs.put(jobId, job);
        publishImageJob(job);

        String accept = request.getHeader(HttpHeaders.ACCEPT);
        ImageProxyRequestBody requestBody = readImageProxyRequestBody(userId, request, config, label);
        job.setPromptMetadata(requestBody.originalPrompt(), requestBody.optimizedPrompt());
        String contentType = requestBody.contentType();
        byte[] body = requestBody.body();
        String targetUrl = joinUrl(config.baseUrl(), path);

        imageJobExecutor.submit(() -> runImageJob(job, targetUrl, config.apiKey(), accept, contentType, body));
        return ResponseEntity.accepted().body(toJobResponse(job));
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

    public AiProxyDtos.ImageReferenceAnalysisResponse analyzeImageReferences(
            UUID userId,
            AiProxyDtos.ImageReferenceAnalysisRequest request
    ) {
        UserApiConfigDtos.Response config = userApiConfigService.getRequiredConfig(userId);
        long startedAt = System.currentTimeMillis();

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.chatModel());
            body.put("instructions", getImageReferenceAnalyzerSystemPrompt());
            body.put("input", buildImageReferenceAnalyzerInput(request));

            log.info(
                    "[image-reference-analyzer] start model={} promptChars={} images={}",
                    config.chatModel(),
                    request.prompt().length(),
                    request.images().size()
            );

            HttpRequest upstreamRequest = HttpRequest.newBuilder(URI.create(joinUrl(config.baseUrl(), "responses")))
                    .timeout(IMAGE_REFERENCE_ANALYZER_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (upstreamResponse.statusCode() < 200 || upstreamResponse.statusCode() > 299) {
                String detail = getBodyPreview(upstreamResponse.body());
                log.warn(
                        "[image-reference-analyzer] upstream error status={} duration={}ms body={}",
                        upstreamResponse.statusCode(),
                        System.currentTimeMillis() - startedAt,
                        detail
                );
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "IMAGE_REFERENCE_ANALYZER_UPSTREAM_ERROR",
                        "图片角色分析上游错误：%s（状态 %d）".formatted(detail, upstreamResponse.statusCode())
                );
            }

            JsonNode data = objectMapper.readTree(upstreamResponse.body());
            AiProxyDtos.ImageReferenceAnalysisResponse analysis = parseImageReferenceAnalysis(
                    extractTextFromAiResponse(data),
                    request
            );

            log.info(
                    "[image-reference-analyzer] done duration={}ms baseImageId={} refs={}",
                    System.currentTimeMillis() - startedAt,
                    analysis.baseImageId(),
                    analysis.referenceImageIds().size()
            );
            return analysis;
        } catch (HttpTimeoutException exception) {
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "IMAGE_REFERENCE_ANALYZER_TIMEOUT",
                    "图片角色分析超过 45 秒，请稍后重试：%s".formatted(safeMessage(exception))
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "IMAGE_REFERENCE_ANALYZER_INTERRUPTED",
                    "图片角色分析请求被中断：%s".formatted(safeMessage(exception))
            );
        } catch (ApiException exception) {
            throw exception;
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

        try {
            ImageProxyResult result = executeImageRequest(job.label(), targetUrl, apiKey, accept, contentType, body);
            if (result.statusCode() >= 200 && result.statusCode() <= 299) {
                job.markCompleted(result);
                publishImageJob(job);
                return;
            }

            job.markFailed(result.statusCode(), result.body(), result.contentType(), result.attempts(), result.requestId());
            publishImageJob(job);
        } catch (Exception exception) {
            log.error("[image-job] {} failed jobId={} error={}", job.label(), job.id(), safeMessage(exception));
            try {
                ImageProxyResult result = jsonImageProxyResult(HttpStatus.BAD_GATEWAY, Map.of(
                        "error", "%s upstream request failed".formatted(job.label()),
                        "detail", safeMessage(exception)
                ), 0);
                job.markFailed(result.statusCode(), result.body(), result.contentType(), result.attempts(), result.requestId());
                publishImageJob(job);
            } catch (IOException ioException) {
                job.markFailed(502, safeMessage(exception).getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", 0, "");
                publishImageJob(job);
            }
        }
    }

    private void publishImageJob(ImageJobState job) {
        imageJobEventBroadcaster.publishImageJob(job.userId(), toJobResponse(job));
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

    private ImageProxyRequestBody readImageProxyRequestBody(
            UUID userId,
            HttpServletRequest request,
            UserApiConfigDtos.Response config,
            String label
    ) throws IOException {
        String contentType = request.getContentType();
        if (!isMultipartFormData(contentType)) {
            return readJsonImageProxyRequestBody(config, label, contentType, request.getInputStream().readAllBytes());
        }

        try {
            String boundary = "----LivartProxyBoundary" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            List<MultipartPartData> passthroughParts = new ArrayList<>();
            List<MultipartPartData> uploadedImageParts = new ArrayList<>();
            List<UUID> referenceAssetIds = new ArrayList<>();
            UUID imageAssetId = null;
            String prompt = "";
            String imageContext = "";
            String promptOptimizationMode = label;

            for (Part part : request.getParts()) {
                MultipartPartData partData = readMultipartPart(part);
                if ("imageAssetId".equals(partData.name())) {
                    imageAssetId = parseAssetId(readUtf8(partData.body()), "imageAssetId");
                } else if ("referenceAssetId".equals(partData.name())) {
                    referenceAssetIds.add(parseAssetId(readUtf8(partData.body()), "referenceAssetId"));
                } else if ("image".equals(partData.name())) {
                    uploadedImageParts.add(partData);
                } else if (isPromptContextField(partData.name())) {
                    imageContext = firstNonBlank(imageContext, readUtf8(partData.body()));
                } else if ("promptOptimizationMode".equals(partData.name())) {
                    promptOptimizationMode = normalizePromptOptimizationMode(readUtf8(partData.body()), label);
                } else {
                    if ("prompt".equals(partData.name())) {
                        prompt = firstNonBlank(prompt, readUtf8(partData.body()));
                    }
                    passthroughParts.add(partData);
                }
            }

            String optimizedPrompt = optimizePromptInline(config, promptOptimizationMode, prompt, imageContext);
            for (MultipartPartData part : passthroughParts) {
                writeMultipartPart(output, boundary, "prompt".equals(part.name())
                        ? withUtf8Body(part, optimizedPrompt)
                        : part);
            }

            if (imageAssetId != null) {
                writeAssetImagePart(output, boundary, userId, imageAssetId);
            } else if (!uploadedImageParts.isEmpty()) {
                writeMultipartPart(output, boundary, prepareUploadedImagePart(uploadedImageParts.get(0)));
            }

            for (UUID referenceAssetId : referenceAssetIds) {
                writeAssetImagePart(output, boundary, userId, referenceAssetId);
            }

            int firstUploadedReferenceIndex = imageAssetId == null && !uploadedImageParts.isEmpty() ? 1 : 0;
            for (int index = firstUploadedReferenceIndex; index < uploadedImageParts.size(); index += 1) {
                writeMultipartPart(output, boundary, prepareUploadedImagePart(uploadedImageParts.get(index)));
            }

            writeAscii(output, "--" + boundary + "--");
            output.write(CRLF);
            return new ImageProxyRequestBody("multipart/form-data; boundary=" + boundary, output.toByteArray(), prompt, optimizedPrompt);
        } catch (ServletException exception) {
            throw new IOException("Failed to read multipart image request", exception);
        }
    }

    private ImageProxyRequestBody readJsonImageProxyRequestBody(
            UserApiConfigDtos.Response config,
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

        return new ImageProxyRequestBody(contentType, objectMapper.writeValueAsBytes(rewrittenBody), prompt, optimizedPrompt);
    }

    private MultipartPartData readMultipartPart(Part part) throws IOException {
        try (InputStream inputStream = part.getInputStream()) {
            return new MultipartPartData(
                    part.getName(),
                    getSubmittedFileName(part).orElse(""),
                    part.getContentType(),
                    inputStream.readAllBytes()
            );
        }
    }

    private String readUtf8(byte[] body) {
        return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8).trim();
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
        if ("image-remover".equals(normalizedValue)) {
            return normalizedValue;
        }
        return fallbackMode;
    }

    private MultipartPartData withUtf8Body(MultipartPartData part, String value) {
        return new MultipartPartData(
                part.name(),
                part.filename(),
                part.contentType(),
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private UUID parseAssetId(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ASSET_ID", "%s 无效".formatted(fieldName));
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

    private MultipartPartData prepareUploadedImagePart(MultipartPartData part) {
        AssetService.PreparedImageContent preparedImage = assetService.prepareModelInputImage(
                part.body(),
                part.contentType()
        );
        return new MultipartPartData(
                part.name(),
                modelInputFilename(null, part.filename(), preparedImage.contentType()),
                preparedImage.contentType(),
                preparedImage.bytes()
        );
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

    private boolean isMultipartFormData(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data");
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

    private Optional<String> getSubmittedFileName(Part part) {
        try {
            return Optional.ofNullable(part.getSubmittedFileName());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String escapeMultipartHeader(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String optimizePromptInline(
            UserApiConfigDtos.Response config,
            String mode,
            String prompt,
            String imageContext
    ) {
        String trimmedPrompt = prompt == null ? "" : prompt.trim();
        if (trimmedPrompt.isBlank()) {
            return prompt == null ? "" : prompt;
        }

        if ("image-remover".equals(mode)) {
            return buildDeterministicRemoverPrompt(trimmedPrompt);
        }

        String trimmedImageContext = imageContext == null ? "" : imageContext.trim();
        long startedAt = System.currentTimeMillis();
        try {
            String systemPrompt = getPromptOptimizerSystemPrompt(mode);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.chatModel());
            body.put("instructions", systemPrompt);
            body.put("input", buildPromptOptimizerInput(trimmedPrompt, trimmedImageContext));

            log.info(
                    "[prompt-optimizer] inline start mode={} model={} promptChars={} contextChars={}",
                    mode,
                    config.chatModel(),
                    trimmedPrompt.length(),
                    trimmedImageContext.length()
            );
            HttpRequest upstreamRequest = HttpRequest.newBuilder(URI.create(joinUrl(config.baseUrl(), "responses")))
                    .timeout(PROMPT_OPTIMIZER_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (upstreamResponse.statusCode() < 200 || upstreamResponse.statusCode() > 299) {
                String detail = getBodyPreview(upstreamResponse.body());
                log.warn(
                        "[prompt-optimizer] inline upstream error mode={} status={} duration={}ms body={}",
                        mode,
                        upstreamResponse.statusCode(),
                        System.currentTimeMillis() - startedAt,
                        detail
                );
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "PROMPT_OPTIMIZER_UPSTREAM_ERROR",
                        "提示词优化上游错误：%s（状态 %d）".formatted(detail, upstreamResponse.statusCode())
                );
            }

            JsonNode data = objectMapper.readTree(upstreamResponse.body());
            String optimizedPrompt = appendNegativePromptConstraints(sanitizeOptimizedPrompt(extractTextFromAiResponse(data)));

            log.info(
                    "[prompt-optimizer] inline done mode={} duration={}ms optimizedChars={}",
                    mode,
                    System.currentTimeMillis() - startedAt,
                    optimizedPrompt.length()
            );
            return optimizedPrompt;
        } catch (HttpTimeoutException exception) {
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "PROMPT_OPTIMIZER_TIMEOUT",
                    "提示词优化超过 2 分钟，请稍后重试：%s".formatted(safeMessage(exception))
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PROMPT_OPTIMIZER_INTERRUPTED",
                    "提示词优化请求被中断：%s".formatted(safeMessage(exception))
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("[prompt-optimizer] inline failed mode={} duration={}ms error={}", mode, System.currentTimeMillis() - startedAt, safeMessage(exception));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PROMPT_OPTIMIZER_FAILED",
                    "提示词优化失败：%s".formatted(safeMessage(exception))
            );
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

    private Map<String, Object> toJobErrorPayload(ImageJobState job) {
        Object upstreamPayload = parseResponsePayload(job.body(), job.contentType());
        Map<String, Object> error = new LinkedHashMap<>();
        String message = extractUpstreamErrorMessage(upstreamPayload, job.body());
        String code = extractUpstreamErrorCode(upstreamPayload);
        String type = extractUpstreamErrorType(upstreamPayload);

        error.put("message", message.isBlank() ? "上游 AI 接口调用失败" : message);
        error.put("upstreamStatus", job.upstreamStatus());
        error.put("attempts", job.attempts());
        if (!code.isBlank()) {
            error.put("code", code);
        }
        if (!type.isBlank()) {
            error.put("type", type);
        }
        if (job.requestId() != null && !job.requestId().isBlank()) {
            error.put("requestId", job.requestId());
        }
        if (upstreamPayload != null) {
            error.put("upstream", upstreamPayload);
        }

        return error;
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
                - 如果输入中包含“图片角色分析”，必须先理解原图/编辑目标和素材参考的关系，再优化用户原始指令；不要把“图片角色分析”标题和内部说明原样输出。
                - 原文中任何 @xxx 图片引用标记都是系统占位符，必须逐字保留，不要翻译、删除、换序或改写。
                - 补充清晰的主体、场景、构图、风格、材质、色彩、光影、镜头/视角和质量描述。
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

        return """
                %s
                - 当前任务是文生图，需要把短描述扩写成可直接用于高质量图像生成的完整视觉 brief。""".formatted(sharedRules);
    }

    private String buildDeterministicRemoverPrompt(String prompt) {
        return "把圈起来的地方删除掉。";
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

    private String buildPromptOptimizerInput(String prompt, String imageContext) {
        if (imageContext == null || imageContext.isBlank()) {
            return "原始提示词：" + prompt;
        }

        return """
                %s

                输出要求：只输出优化后的用户提示词；必须遵守上面的图片角色分析；不要输出“图片角色分析”标题、推理过程或解释。

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

    private void addPromptMetadataHeaders(HttpHeaders headers, String originalPrompt, String optimizedPrompt) {
        if (originalPrompt != null && !originalPrompt.isBlank()) {
            headers.add(ORIGINAL_PROMPT_HEADER, encodePromptHeader(originalPrompt));
        }
        if (optimizedPrompt != null && !optimizedPrompt.isBlank()) {
            headers.add(OPTIMIZED_PROMPT_HEADER, encodePromptHeader(optimizedPrompt));
        }
    }

    private String encodePromptHeader(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record ImageProxyResult(
            int statusCode,
            byte[] body,
            String contentType,
            int attempts,
            String requestId
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
