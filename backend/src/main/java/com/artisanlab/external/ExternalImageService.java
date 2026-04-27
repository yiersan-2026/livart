package com.artisanlab.external;

import com.artisanlab.asset.AssetDtos;
import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ExternalImageService {
    private static final Logger log = LoggerFactory.getLogger(ExternalImageService.class);
    private static final int MAX_IMAGE_COUNT = 80;
    private static final int MAX_IMPORT_BYTES = 25 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING_URL_PUNCTUATION = "，。！？；：、,.!?;:)]}）】》\"'`";
    private static final Set<String> IMAGE_CONTAINER_FIELDS = Set.of(
            "images", "imageUrls", "image_urls", "urls", "pictures", "pics", "photos",
            "data", "result", "results", "items", "resources", "list", "media"
    );
    private static final List<String> IMAGE_URL_FIELDS = List.of(
            "url", "src", "image", "imageUrl", "image_url", "originUrl", "origin_url",
            "original", "originalUrl", "original_url", "downloadUrl", "download_url",
            "displayUrl", "display_url", "href"
    );
    private static final List<String> THUMBNAIL_FIELDS = List.of(
            "previewUrl", "preview_url", "thumbnail", "thumbnailUrl", "thumbnail_url", "thumb", "thumbUrl", "thumb_url",
            "preview", "previewUrl", "preview_url", "cover", "coverUrl", "cover_url"
    );

    private final ArtisanProperties properties;
    private final ObjectMapper objectMapper;
    private final AssetService assetService;
    private final ExternalImageParseHistoryMapper parseHistoryMapper;
    private final HttpClient httpClient;

    public ExternalImageService(
            ArtisanProperties properties,
            ObjectMapper objectMapper,
            AssetService assetService,
            ExternalImageParseHistoryMapper parseHistoryMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.assetService = assetService;
        this.parseHistoryMapper = parseHistoryMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ExternalImageDtos.SearchResponse search(
            UUID userId,
            ExternalImageDtos.SearchRequest request
    ) {
        requireConfigured();
        String sourceUrl = extractFirstHttpUrl(request.url(), "请输入有效的社交媒体链接");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("url", sourceUrl);

        HttpRequest upstreamRequest = HttpRequest.newBuilder(URI.create(properties.externalImages().endpoint().trim()))
                .timeout(Duration.ofSeconds(safeTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-API-Key", properties.externalImages().apiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.info("[external-images] search status={} durationMs={} sourceHost={}",
                    response.statusCode(), System.currentTimeMillis() - startedAt, URI.create(sourceUrl).getHost());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGES_UPSTREAM_ERROR", upstreamErrorMessage(response.statusCode(), response.body()));
            }

            List<ExternalImageDtos.ImageCandidate> images = parseImageCandidates(response.body());
            if (images.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGES_EMPTY", "没有从这个链接解析到图片");
            }
            saveParseHistory(userId, sourceUrl, images.size());
            return new ExternalImageDtos.SearchResponse(sourceUrl, images);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGES_REQUEST_FAILED", "社交媒体图片接口请求失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGES_REQUEST_INTERRUPTED", "社交媒体图片接口请求被中断");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGES_RESPONSE_INVALID", "社交媒体图片接口返回格式无法识别");
        }
    }

    private void saveParseHistory(UUID userId, String sourceUrl, int imageCount) {
        if (parseHistoryMapper == null) return;

        try {
            URI sourceUri = URI.create(sourceUrl);
            ExternalImageParseHistoryEntity entity = new ExternalImageParseHistoryEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId(userId);
            entity.setSourceUrl(sourceUrl);
            entity.setSourceHost(sourceUri.getHost());
            entity.setImageCount(Math.max(0, imageCount));
            parseHistoryMapper.upsert(entity);
        } catch (Exception exception) {
            log.warn("[external-images] save parse history failed userId={} sourceHost={} error={}",
                    userId, safeHost(sourceUrl), exception.getMessage());
        }
    }

    private String safeHost(String sourceUrl) {
        try {
            return URI.create(sourceUrl).getHost();
        } catch (Exception exception) {
            return "";
        }
    }

    public ExternalImageDtos.ImportedImageResponse importImage(
            UUID userId,
            ExternalImageDtos.ImportRequest request
    ) {
        DownloadedImage downloadedImage = downloadImage(request.url(), request.filename());
        AssetDtos.AssetResponse asset = assetService.uploadBytes(
                userId,
                request.canvasId(),
                downloadedImage.filename(),
                downloadedImage.contentType(),
                downloadedImage.bytes()
        );
        return new ExternalImageDtos.ImportedImageResponse(
                asset.id(),
                asset.urlPath(),
                asset.previewUrlPath(),
                asset.thumbnailUrlPath(),
                asset.originalFilename(),
                asset.mimeType(),
                asset.sizeBytes(),
                asset.width(),
                asset.height(),
                asset.createdAt(),
                request.url()
        );
    }

    private List<ExternalImageDtos.ImageCandidate> parseImageCandidates(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }

        List<ParsedImage> parsedImages = new ArrayList<>();
        collectImages(root, parsedImages);
        Set<String> seenUrls = new LinkedHashSet<>();
        List<ExternalImageDtos.ImageCandidate> candidates = new ArrayList<>();
        for (ParsedImage parsedImage : parsedImages) {
            if (!seenUrls.add(parsedImage.url())) continue;
            candidates.add(new ExternalImageDtos.ImageCandidate(
                    "social-" + candidates.size(),
                    parsedImage.url(),
                    StringUtils.hasText(parsedImage.thumbnailUrl()) ? parsedImage.thumbnailUrl() : parsedImage.url(),
                    parsedImage.title(),
                    parsedImage.formatLabel(),
                    parsedImage.mimeType(),
                    parsedImage.width(),
                    parsedImage.height(),
                    parsedImage.fileSizeBytes(),
                    parsedImage.watermarked(),
                    parsedImage.sortOrder()
            ));
            if (candidates.size() >= MAX_IMAGE_COUNT) break;
        }
        return candidates;
    }

    private void collectImages(JsonNode node, List<ParsedImage> images) {
        if (node == null || node.isNull() || images.size() >= MAX_IMAGE_COUNT) return;

        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (isImportableImageValue(value)) {
                images.add(new ParsedImage(value, "", "", "", "", null, null, null, null, null));
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectImages(child, images);
                if (images.size() >= MAX_IMAGE_COUNT) return;
            }
            return;
        }

        if (!node.isObject()) return;

        String imageUrl = firstTextField(node, IMAGE_URL_FIELDS);
        if (isImportableImageValue(imageUrl)) {
            images.add(new ParsedImage(
                    imageUrl,
                    firstTextField(node, THUMBNAIL_FIELDS),
                    firstTextField(node, List.of("title", "alt", "name", "description", "formatLabel", "format_label")),
                    firstTextField(node, List.of("formatLabel", "format_label", "label", "name")),
                    firstTextField(node, List.of("mimeType", "mime_type", "contentType", "content_type", "type")),
                    firstIntField(node, List.of("width", "w")),
                    firstIntField(node, List.of("height", "h")),
                    firstLongField(node, List.of("fileSizeBytes", "file_size_bytes", "sizeBytes", "size_bytes", "fileSize", "file_size", "size")),
                    firstBooleanField(node, List.of("watermarked", "hasWatermark", "has_watermark")),
                    firstNonNegativeIntField(node, List.of("sortOrder", "sort_order", "order", "index"))
            ));
        }

        node.fields().forEachRemaining(entry -> {
            if (images.size() >= MAX_IMAGE_COUNT) return;
            if (IMAGE_CONTAINER_FIELDS.contains(entry.getKey()) || entry.getValue().isArray()) {
                collectImages(entry.getValue(), images);
            }
        });
    }

    private DownloadedImage downloadImage(String value, String requestedFilename) {
        String trimmedValue = value == null ? "" : value.trim();
        if (trimmedValue.startsWith("data:image/")) {
            return decodeDataImage(trimmedValue, requestedFilename);
        }

        URI imageUri = URI.create(validateHttpUrl(trimmedValue, "图片链接无效"));
        HttpResponse<byte[]> response = sendImageDownloadRequest(imageUri, 0);
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_EMPTY", "下载到的图片为空");
        }
        if (bytes.length > MAX_IMPORT_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "EXTERNAL_IMAGE_TOO_LARGE", "图片超过 25MB，无法导入");
        }

        String contentType = normalizeContentType(response.headers().firstValue("content-type").orElse("image/png"));
        String filename = StringUtils.hasText(requestedFilename)
                ? requestedFilename.trim()
                : filenameFromUri(imageUri, contentType);
        return new DownloadedImage(bytes, contentType, filename);
    }

    private HttpResponse<byte[]> sendImageDownloadRequest(URI uri, int redirectCount) {
        validatePublicHttpUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(safeTimeoutSeconds()))
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,image/svg+xml,image/*;q=0.8,*/*;q=0.2")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_REDIRECT_LIMIT", "图片链接重定向次数过多");
                }
                String location = response.headers().firstValue("location").orElse("");
                if (!StringUtils.hasText(location)) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_REDIRECT_INVALID", "图片链接重定向地址为空");
                }
                return sendImageDownloadRequest(uri.resolve(location), redirectCount + 1);
            }
            if (status < 200 || status >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_DOWNLOAD_FAILED", "图片下载失败（状态 %d）".formatted(status));
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_DOWNLOAD_FAILED", "图片下载失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_DOWNLOAD_INTERRUPTED", "图片下载被中断");
        }
    }

    private DownloadedImage decodeDataImage(String dataUrl, String requestedFilename) {
        int commaIndex = dataUrl.indexOf(',');
        int semicolonIndex = dataUrl.indexOf(';');
        if (commaIndex <= 0 || semicolonIndex <= 5 || !dataUrl.substring(semicolonIndex + 1, commaIndex).contains("base64")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_IMAGE_DATA_URL_INVALID", "图片数据格式无效");
        }
        String contentType = normalizeContentType(dataUrl.substring("data:".length(), semicolonIndex));
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(commaIndex + 1));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_IMAGE_DATA_URL_INVALID", "图片数据无法解码");
        }
        if (bytes.length > MAX_IMPORT_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "EXTERNAL_IMAGE_TOO_LARGE", "图片超过 25MB，无法导入");
        }
        return new DownloadedImage(bytes, contentType, StringUtils.hasText(requestedFilename) ? requestedFilename.trim() : "social-image" + extensionForContentType(contentType));
    }

    private String upstreamErrorMessage(int statusCode, String responseBody) {
        String message = "";
        String code = "";
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? "" : responseBody);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                code = error.path("code").asText("");
                message = error.path("message").asText("");
            }
            if (!StringUtils.hasText(message)) {
                message = root.path("message").asText("");
            }
        } catch (IOException ignored) {
        }
        if (!StringUtils.hasText(message)) {
            message = "接口返回异常";
        }
        if (StringUtils.hasText(code)) {
            message = "%s（%s）".formatted(message, code);
        }
        return "社交媒体图片接口错误：%s（状态 %d）".formatted(message, statusCode);
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(properties.externalImages().apiKey())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_IMAGES_API_KEY_MISSING", "社交媒体图片接口 Key 未配置");
        }
        validateHttpUrl(properties.externalImages().endpoint(), "社交媒体图片接口地址无效");
    }

    private long safeTimeoutSeconds() {
        return Math.max(5, Math.min(120, properties.externalImages().timeoutSeconds()));
    }

    private String validateHttpUrl(String value, String message) {
        String trimmedValue = value == null ? "" : value.trim();
        URI uri;
        try {
            uri = URI.create(trimmedValue);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_URL", message);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_URL", message);
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_URL", message);
        }
        return uri.toString();
    }

    private String extractFirstHttpUrl(String value, String message) {
        String trimmedValue = value == null ? "" : value.trim();
        Matcher matcher = HTTP_URL_PATTERN.matcher(trimmedValue);
        if (!matcher.find()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_URL", message);
        }
        return validateHttpUrl(trimTrailingUrlPunctuation(matcher.group()), message);
    }

    private String trimTrailingUrlPunctuation(String value) {
        String trimmedValue = value == null ? "" : value.trim();
        while (!trimmedValue.isEmpty()) {
            char lastChar = trimmedValue.charAt(trimmedValue.length() - 1);
            if (TRAILING_URL_PUNCTUATION.indexOf(lastChar) < 0) {
                break;
            }
            trimmedValue = trimmedValue.substring(0, trimmedValue.length() - 1);
        }
        return trimmedValue;
    }

    private void validatePublicHttpUri(URI uri) {
        validateHttpUrl(uri.toString(), "图片链接无效");
        String host = IDN.toASCII(uri.getHost());
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_IMAGE_PRIVATE_URL", "不能导入内网或本机图片地址");
                }
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_IMAGE_HOST_INVALID", "图片链接域名无法解析");
        }
    }

    private String firstTextField(JsonNode node, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private Integer firstIntField(JsonNode node, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.canConvertToInt() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private Integer firstNonNegativeIntField(JsonNode node, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.canConvertToInt() && value.asInt() >= 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private Long firstLongField(JsonNode node, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.canConvertToLong() && value.asLong() > 0) {
                return value.asLong();
            }
        }
        return null;
    }

    private Boolean firstBooleanField(JsonNode node, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isBoolean()) {
                return value.asBoolean();
            }
        }
        return null;
    }

    private boolean isImportableImageValue(String value) {
        if (!StringUtils.hasText(value)) return false;
        String trimmedValue = value.trim();
        return trimmedValue.startsWith("data:image/") || isHttpImageCandidate(trimmedValue);
    }

    private boolean isHttpImageCandidate(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizeContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return StringUtils.hasText(normalized) ? normalized : "image/png";
    }

    private String filenameFromUri(URI uri, String contentType) {
        String path = uri.getPath();
        String filename = "";
        if (StringUtils.hasText(path)) {
            int slashIndex = path.lastIndexOf('/');
            filename = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
        }
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            filename = "social-image" + extensionForContentType(contentType);
        }
        return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
    }

    private String extensionForContentType(String contentType) {
        return switch (normalizeContentType(contentType)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    private record ParsedImage(
            String url,
            String thumbnailUrl,
            String title,
            String formatLabel,
            String mimeType,
            Integer width,
            Integer height,
            Long fileSizeBytes,
            Boolean watermarked,
            Integer sortOrder
    ) {
    }

    private record DownloadedImage(byte[] bytes, String contentType, String filename) {
    }
}
