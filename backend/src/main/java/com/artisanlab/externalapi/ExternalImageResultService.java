package com.artisanlab.externalapi;

import com.artisanlab.asset.AssetDtos;
import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ExternalImageResultService {
    private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;

    private final AssetService assetService;
    private final ExternalGeneratedImageMapper mapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExternalImageResultService(
            AssetService assetService,
            ExternalGeneratedImageMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.assetService = assetService;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Transactional
    public List<StoredImage> persistJobResult(UUID ownerId, UUID jobId, byte[] body, String contentType) {
        return persistJobResult(ownerId, jobId, "external-image", body, contentType);
    }

    @Transactional
    public List<StoredImage> persistJobResult(UUID ownerId, UUID jobId, String label, byte[] body, String contentType) {
        List<StoredImage> existingImages = listStoredImages(ownerId, jobId);
        if (!existingImages.isEmpty()) {
            return existingImages;
        }

        List<DecodedImage> decodedImages = decodeResultImages(body, contentType);
        if (decodedImages.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_RESULT_EMPTY", "上游返回成功，但未解析到图片结果");
        }

        List<StoredImage> storedImages = new ArrayList<>();
        for (int index = 0; index < decodedImages.size(); index += 1) {
            DecodedImage decodedImage = decodedImages.get(index);
            AssetDtos.AssetResponse asset = assetService.uploadBytes(
                    null,
                    null,
                    decodedImage.filename(),
                    decodedImage.contentType(),
                    decodedImage.bytes()
            );

            ExternalGeneratedImageEntity entity = new ExternalGeneratedImageEntity();
            entity.setId(UUID.randomUUID());
            entity.setJobId(jobId);
            entity.setOwnerId(ownerId);
            entity.setAssetId(asset.id());
            entity.setLabel(normalizeLabel(label));
            entity.setImageIndex(index);
            mapper.insertMapping(entity);

            storedImages.add(toStoredImage(entity.getId(), index, asset));
        }
        return List.copyOf(storedImages);
    }

    public List<StoredImage> listStoredImages(UUID ownerId, UUID jobId) {
        return mapper.selectByOwnerIdAndJobId(ownerId, jobId).stream()
                .map(this::toStoredImage)
                .toList();
    }

    private List<DecodedImage> decodeResultImages(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return List.of();
        }
        String normalizedContentType = normalizeContentType(contentType);
        if (normalizedContentType.startsWith("image/")) {
            return List.of(new DecodedImage(body, normalizedContentType, defaultFilename(1, normalizedContentType)));
        }
        if (!normalizedContentType.contains("json") && !looksLikeJson(body)) {
            return List.of();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_RESULT_INVALID", "上游返回结果无法解析");
        }

        List<JsonNode> candidates = extractCandidates(root);
        List<DecodedImage> images = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index += 1) {
            DecodedImage decodedImage = decodeCandidate(candidates.get(index), index + 1);
            if (decodedImage != null) {
                images.add(decodedImage);
            }
        }
        return List.copyOf(images);
    }

    private List<JsonNode> extractCandidates(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            return toNodeList(root);
        }
        if (root.isObject()) {
            if (root.has("data")) {
                return asNodeList(root.get("data"));
            }
            if (root.has("images")) {
                return asNodeList(root.get("images"));
            }
            if (hasSupportedImageField(root)) {
                return List.of(root);
            }
            if (root.has("result")) {
                return asNodeList(root.get("result"));
            }
            if (root.has("output")) {
                return asNodeList(root.get("output"));
            }
            if (root.has("image")) {
                return asNodeList(root.get("image"));
            }
        }
        return List.of();
    }

    private List<JsonNode> asNodeList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            return toNodeList(node);
        }
        return List.of(node);
    }

    private List<JsonNode> toNodeList(JsonNode arrayNode) {
        List<JsonNode> nodes = new ArrayList<>();
        arrayNode.forEach(nodes::add);
        return List.copyOf(nodes);
    }

    private DecodedImage decodeCandidate(JsonNode candidate, int index) {
        if (candidate == null || candidate.isNull()) {
            return null;
        }
        if (candidate.isTextual()) {
            return decodeStringValue(candidate.asText(""), index);
        }
        if (!candidate.isObject()) {
            return null;
        }
        if (candidate.hasNonNull("b64_json")) {
            return decodeBase64Value(candidate.get("b64_json").asText(""), index, "image/png");
        }
        if (candidate.hasNonNull("b64Json")) {
            return decodeBase64Value(candidate.get("b64Json").asText(""), index, "image/png");
        }
        if (candidate.hasNonNull("url")) {
            return downloadImage(candidate.get("url").asText(""), index);
        }
        if (candidate.hasNonNull("image_url")) {
            return downloadImage(candidate.get("image_url").asText(""), index);
        }
        if (candidate.hasNonNull("image")) {
            return decodeStringValue(candidate.get("image").asText(""), index);
        }
        return null;
    }

    private DecodedImage decodeStringValue(String value, int index) {
        String trimmedValue = value == null ? "" : value.trim();
        if (!StringUtils.hasText(trimmedValue)) {
            return null;
        }
        if (trimmedValue.startsWith("data:image/")) {
            return decodeDataUrl(trimmedValue, index);
        }
        if (looksLikeHttpUrl(trimmedValue)) {
            return downloadImage(trimmedValue, index);
        }
        return decodeBase64Value(trimmedValue, index, "image/png");
    }

    private DecodedImage decodeBase64Value(String value, int index, String contentType) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_RESULT_INVALID", "上游返回图片数据无法解码");
        }
        if (bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_RESULT_INVALID", "上游返回图片内容为空");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_TOO_LARGE", "上游返回图片过大，无法存储");
        }
        return new DecodedImage(bytes, normalizeContentType(contentType), defaultFilename(index, contentType));
    }

    private DecodedImage decodeDataUrl(String value, int index) {
        int commaIndex = value.indexOf(',');
        int semicolonIndex = value.indexOf(';');
        if (commaIndex <= 0 || semicolonIndex <= 5 || !value.substring(semicolonIndex + 1, commaIndex).contains("base64")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_RESULT_INVALID", "上游返回图片 Data URL 格式无效");
        }
        String contentType = normalizeContentType(value.substring("data:".length(), semicolonIndex));
        return decodeBase64Value(value.substring(commaIndex + 1), index, contentType);
    }

    private DecodedImage downloadImage(String url, int index) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_RESULT_INVALID", "上游返回图片地址无效");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "image/*")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_DOWNLOAD_FAILED", "下载上游图片失败（状态 %d）".formatted(response.statusCode()));
            }
            String contentType = normalizeContentType(response.headers().firstValue("Content-Type").orElse("image/png"));
            byte[] bytes = readLimitedBytes(response.body());
            return new DecodedImage(bytes, contentType, filenameFromUri(uri, contentType, index));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_DOWNLOAD_FAILED", "下载上游图片失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_DOWNLOAD_INTERRUPTED", "下载上游图片被中断");
        }
    }

    private byte[] readLimitedBytes(InputStream inputStream) throws IOException {
        try (InputStream stream = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_IMAGE_TOO_LARGE", "上游返回图片过大，无法存储");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private boolean hasSupportedImageField(JsonNode node) {
        return node.hasNonNull("b64_json")
                || node.hasNonNull("b64Json")
                || node.hasNonNull("url")
                || node.hasNonNull("image_url")
                || node.hasNonNull("image");
    }

    private boolean looksLikeJson(byte[] body) {
        String preview = new String(body, 0, Math.min(body.length, 64), StandardCharsets.UTF_8).trim();
        return preview.startsWith("{") || preview.startsWith("[");
    }

    private boolean looksLikeHttpUrl(String value) {
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

    private String filenameFromUri(URI uri, String contentType, int index) {
        String path = uri.getPath();
        if (StringUtils.hasText(path)) {
            int slashIndex = path.lastIndexOf('/');
            String filename = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
            if (StringUtils.hasText(filename) && filename.contains(".")) {
                return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
            }
        }
        return defaultFilename(index, contentType);
    }

    private String defaultFilename(int index, String contentType) {
        return "external-job-%02d%s".formatted(index, extensionForContentType(contentType));
    }

    private String extensionForContentType(String contentType) {
        return switch (normalizeContentType(contentType)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    private String normalizeLabel(String label) {
        String normalized = label == null ? "" : label.trim();
        return normalized.isBlank() ? "external-image" : normalized;
    }

    private StoredImage toStoredImage(ExternalGeneratedImageEntity entity) {
        return new StoredImage(
                entity.getId(),
                entity.getAssetId(),
                entity.getImageIndex(),
                entity.getUrlPath(),
                "/api/assets/%s/preview".formatted(entity.getAssetId()),
                "/api/assets/%s/thumbnail".formatted(entity.getAssetId()),
                entity.getOriginalFilename(),
                entity.getMimeType(),
                entity.getSizeBytes(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getCreatedAt()
        );
    }

    private StoredImage toStoredImage(UUID mappingId, int imageIndex, AssetDtos.AssetResponse asset) {
        return new StoredImage(
                mappingId,
                asset.id(),
                imageIndex,
                asset.urlPath(),
                asset.previewUrlPath(),
                asset.thumbnailUrlPath(),
                asset.originalFilename(),
                asset.mimeType(),
                asset.sizeBytes(),
                asset.width(),
                asset.height(),
                asset.createdAt()
        );
    }

    public record StoredImage(
            UUID id,
            UUID assetId,
            int imageIndex,
            String urlPath,
            String previewUrlPath,
            String thumbnailUrlPath,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            OffsetDateTime createdAt
    ) {
    }

    private record DecodedImage(
            byte[] bytes,
            String contentType,
            String filename
    ) {
    }
}
