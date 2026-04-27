package com.artisanlab.asset;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import com.artisanlab.canvas.CanvasMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssetService {
    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private static final int PREVIEW_MAX_SIDE = 1600;
    private static final int THUMBNAIL_MAX_SIDE = 512;
    private static final int MODEL_INPUT_MAX_SIDE = 2048;
    private static final float PREVIEW_WEBP_QUALITY = 0.82f;
    private static final float THUMBNAIL_WEBP_QUALITY = 0.76f;
    private static final float CANVAS_VIEW_WEBP_QUALITY = 0.82f;
    private static final float MODEL_INPUT_JPEG_QUALITY = 0.86f;
    private static final String WEBP_FORMAT = "webp";
    private static final String WEBP_CONTENT_TYPE = "image/webp";

    private final AssetMapper assetMapper;
    private final CanvasMapper canvasMapper;
    private final ArtisanProperties properties;
    private final MinioClient minioClient;

    public AssetService(AssetMapper assetMapper, CanvasMapper canvasMapper, ArtisanProperties properties) {
        this.assetMapper = assetMapper;
        this.canvasMapper = canvasMapper;
        this.properties = properties;
        this.minioClient = createMinioClient(properties.minio().endpoint(), properties.minio().accessKey(), properties.minio().secretKey());
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
            ImageIO.scanForPlugins();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.minio().bucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.minio().bucket())
                        .build());
            }
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MINIO_UNAVAILABLE", "MinIO 初始化失败");
        }
    }

    @Transactional
    public AssetDtos.AssetResponse upload(UUID userId, UUID canvasId, MultipartFile file) {
        validateImage(file);
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_READ_FAILED", "读取上传图片失败");
        }

        return uploadBytes(userId, canvasId, file.getOriginalFilename(), file.getContentType(), fileBytes);
    }

    @Transactional
    public AssetDtos.AssetResponse uploadBytes(
            UUID userId,
            UUID canvasId,
            String originalFilename,
            String contentType,
            byte[] fileBytes
    ) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请上传图片文件");
        }
        if (canvasId != null && canvasMapper.findByIdAndUserIdWithJson(canvasId, userId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CANVAS_NOT_FOUND", "项目画布不存在");
        }

        UUID assetId = UUID.randomUUID();
        String mimeType = normalizeMimeType(contentType);
        String filename = normalizeFilename(originalFilename);
        BufferedImage image = readImage(fileBytes);
        if (!mimeType.startsWith("image/")) {
            if (image == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", "只支持图片文件");
            }
            mimeType = "image/png";
        }
        String objectKey = "canvases/%s/%s%s".formatted(
                canvasId == null ? "default" : canvasId,
                assetId,
                extensionFor(filename, mimeType)
        );

        ImageSize imageSize = image == null
                ? new ImageSize(null, null)
                : new ImageSize(image.getWidth(), image.getHeight());

        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(objectKey)
                    .stream(inputStream, fileBytes.length, -1)
                    .contentType(mimeType)
                    .build());
            uploadImageVariants(objectKey, image);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSET_UPLOAD_FAILED", "图片上传到 MinIO 失败");
        }

        AssetEntity entity = new AssetEntity();
        entity.setId(assetId);
        entity.setCanvasId(canvasId);
        entity.setUserId(userId);
        entity.setObjectKey(objectKey);
        entity.setUrlPath("/api/assets/%s/content".formatted(assetId));
        entity.setOriginalFilename(filename);
        entity.setMimeType(mimeType);
        entity.setSizeBytes(fileBytes.length);
        entity.setWidth(imageSize.width());
        entity.setHeight(imageSize.height());
        assetMapper.insertAsset(entity);

        return toResponse(assetMapper.findById(assetId));
    }

    private static MinioClient createMinioClient(String endpoint, String accessKey, String secretKey) {
        MinioEndpoint minioEndpoint = parseMinioEndpoint(endpoint);
        return MinioClient.builder()
                .endpoint(minioEndpoint.host(), minioEndpoint.port(), minioEndpoint.secure())
                .credentials(stripWrappingBackticks(accessKey), stripWrappingBackticks(secretKey))
                .build();
    }

    private static MinioEndpoint parseMinioEndpoint(String endpoint) {
        String normalizedEndpoint = stripWrappingBackticks(endpoint).trim();
        if (normalizedEndpoint.isEmpty()) {
            throw new IllegalArgumentException("MinIO endpoint is empty");
        }

        String uriText = normalizedEndpoint.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
                ? normalizedEndpoint
                : "http://" + normalizedEndpoint;
        try {
            URI uri = new URI(uriText);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("MinIO endpoint host is empty");
            }
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
            return new MinioEndpoint(uri.getHost(), port, secure);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid MinIO endpoint", exception);
        }
    }

    private static String stripWrappingBackticks(String value) {
        if (value == null) {
            return "";
        }
        String trimmedValue = value.trim();
        if (trimmedValue.length() >= 2 && trimmedValue.startsWith("`") && trimmedValue.endsWith("`")) {
            return trimmedValue.substring(1, trimmedValue.length() - 1);
        }
        return trimmedValue;
    }

    private record MinioEndpoint(String host, int port, boolean secure) {
    }

    public AssetContent getContent(UUID assetId) {
        AssetEntity entity = assetMapper.findById(assetId);
        if (entity == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片资源不存在");
        }

        return openContent(entity);
    }

    public AssetContent getContentForUser(UUID userId, UUID assetId) {
        return openContent(requireUserAsset(userId, assetId));
    }

    public AssetContent getModelInputContentForUser(UUID userId, UUID assetId) {
        AssetEntity entity = requireUserAsset(userId, assetId);
        byte[] originalBytes = readOriginalBytes(entity);
        PreparedImageContent preparedImage = prepareModelInputImage(originalBytes, entity.getMimeType());
        return new AssetContent(entity, new ByteArrayInputStream(preparedImage.bytes()), preparedImage.contentType());
    }

    public PreparedImageContent prepareModelInputImage(byte[] originalBytes, String contentType) {
        String normalizedContentType = normalizeMimeType(contentType);
        BufferedImage image = readImage(originalBytes);
        if (image == null) {
            return new PreparedImageContent(originalBytes, normalizedContentType);
        }

        try {
            EncodedImage encoded = encodeJpegOrPngVariant(image, MODEL_INPUT_MAX_SIDE, MODEL_INPUT_JPEG_QUALITY);
            boolean resized = Math.max(image.getWidth(), image.getHeight()) > MODEL_INPUT_MAX_SIDE;
            boolean needsCompatibilityConversion = "image/webp".equals(normalizedContentType);
            if (resized || needsCompatibilityConversion || encoded.bytes().length < originalBytes.length) {
                return new PreparedImageContent(encoded.bytes(), encoded.contentType());
            }
        } catch (IOException ignored) {
        }

        return new PreparedImageContent(originalBytes, normalizedContentType);
    }

    private AssetEntity requireUserAsset(UUID userId, UUID assetId) {
        AssetEntity entity = assetMapper.findById(assetId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片资源不存在");
        }

        return entity;
    }

    private AssetContent openContent(AssetEntity entity) {
        try {
            GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(entity.getObjectKey())
                    .build());
            return new AssetContent(entity, object, entity.getMimeType());
        } catch (Exception exception) {
            log.warn("[asset] read failed assetId={} bucket={} objectKey={} error={}",
                    entity.getId(), properties.minio().bucket(), entity.getObjectKey(), exception.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSET_READ_FAILED", "读取图片资源失败");
        }
    }

    private byte[] readOriginalBytes(AssetEntity entity) {
        try (GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.minio().bucket())
                .object(entity.getObjectKey())
                .build())) {
            return object.readAllBytes();
        } catch (Exception exception) {
            log.warn("[asset] read original failed assetId={} bucket={} objectKey={} error={}",
                    entity.getId(), properties.minio().bucket(), entity.getObjectKey(), exception.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSET_READ_FAILED", "读取图片资源失败");
        }
    }

    public AssetContent getPreview(UUID assetId) {
        return getVariantContent(assetId, "preview", PREVIEW_MAX_SIDE, PREVIEW_WEBP_QUALITY);
    }

    public AssetContent getThumbnail(UUID assetId) {
        return getVariantContent(assetId, "thumbnail", THUMBNAIL_MAX_SIDE, THUMBNAIL_WEBP_QUALITY);
    }

    public AssetContent getCanvasView(UUID assetId, int requestedWidth) {
        int width = CanvasViewVariantPolicy.normalizeWidth(requestedWidth);
        return getVariantContent(
                assetId,
                CanvasViewVariantPolicy.variantName(width),
                width,
                CANVAS_VIEW_WEBP_QUALITY,
                ResizeMode.MAX_WIDTH
        );
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请上传图片文件");
        }

        String mimeType = normalizeMimeType(file.getContentType());
        if (!mimeType.startsWith("image/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", "只支持图片文件");
        }
    }

    private BufferedImage readImage(byte[] fileBytes) {
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            return ImageIO.read(inputStream);
        } catch (IOException exception) {
            return null;
        }
    }

    private AssetDtos.AssetResponse toResponse(AssetEntity entity) {
        return new AssetDtos.AssetResponse(
                entity.getId(),
                entity.getCanvasId(),
                entity.getUserId(),
                entity.getUrlPath(),
                previewUrlPath(entity.getId()),
                thumbnailUrlPath(entity.getId()),
                entity.getOriginalFilename(),
                entity.getMimeType(),
                entity.getSizeBytes(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getCreatedAt()
        );
    }

    private String normalizeMimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "canvas-image";
        }
        String normalized = filename.replaceAll("[\\\\/\\r\\n\\t]", "_").trim();
        return normalized.length() > 255 ? normalized.substring(normalized.length() - 255) : normalized;
    }

    private String extensionFor(String filename, String mimeType) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex).toLowerCase(Locale.ROOT);
        }
        return switch (mimeType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    private void uploadImageVariants(String objectKey, BufferedImage image) throws Exception {
        if (image == null) return;
        uploadImageVariant(variantObjectKey(objectKey, "preview", WEBP_FORMAT), image, PREVIEW_MAX_SIDE, PREVIEW_WEBP_QUALITY, ResizeMode.MAX_SIDE);
        uploadImageVariant(variantObjectKey(objectKey, "thumbnail", WEBP_FORMAT), image, THUMBNAIL_MAX_SIDE, THUMBNAIL_WEBP_QUALITY, ResizeMode.MAX_SIDE);
        for (int width : CanvasViewVariantPolicy.WIDTH_TIERS) {
            uploadImageVariant(
                    variantObjectKey(objectKey, CanvasViewVariantPolicy.variantName(width), WEBP_FORMAT),
                    image,
                    width,
                    CANVAS_VIEW_WEBP_QUALITY,
                    ResizeMode.MAX_WIDTH
            );
        }
    }

    private void uploadImageVariant(
            String objectKey,
            BufferedImage source,
            int maxDimension,
            float webpQuality,
            ResizeMode resizeMode
    ) throws Exception {
        EncodedImage encoded = encodeWebpVariant(source, maxDimension, webpQuality, resizeMode);
        try (InputStream inputStream = new ByteArrayInputStream(encoded.bytes())) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(objectKey)
                    .stream(inputStream, encoded.bytes().length, -1)
                    .contentType(encoded.contentType())
                    .build());
        }
    }

    private AssetContent getVariantContent(UUID assetId, String variant, int maxSide, float webpQuality) {
        return getVariantContent(assetId, variant, maxSide, webpQuality, ResizeMode.MAX_SIDE);
    }

    private AssetContent getVariantContent(
            UUID assetId,
            String variant,
            int maxDimension,
            float webpQuality,
            ResizeMode resizeMode
    ) {
        AssetEntity entity = assetMapper.findById(assetId);
        if (entity == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片资源不存在");
        }

        AssetContent webpContent = tryOpenVariant(entity, variantObjectKey(entity.getObjectKey(), variant, WEBP_FORMAT));
        if (webpContent != null) {
            return webpContent;
        }

        AssetContent generatedWebpContent = generateVariantFromOriginal(entity, variant, maxDimension, webpQuality, resizeMode);
        if (generatedWebpContent != null) {
            return generatedWebpContent;
        }

        for (String objectKey : List.of(
                variantObjectKey(entity.getObjectKey(), variant, "jpg"),
                variantObjectKey(entity.getObjectKey(), variant, "png")
        )) {
            AssetContent legacyContent = tryOpenVariant(entity, objectKey);
            if (legacyContent != null) return legacyContent;
        }

        return getContent(entity.getId());
    }

    private AssetContent tryOpenVariant(AssetEntity entity, String objectKey) {
        try {
            GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(objectKey)
                    .build());
            return new AssetContent(entity, object, variantContentType(objectKey));
        } catch (Exception ignored) {
            return null;
        }
    }

    private AssetContent generateVariantFromOriginal(
            AssetEntity entity,
            String variant,
            int maxDimension,
            float webpQuality,
            ResizeMode resizeMode
    ) {
        try (GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.minio().bucket())
                .object(entity.getObjectKey())
                .build())) {
            BufferedImage image = ImageIO.read(object);
            if (image == null) {
                return null;
            }
            EncodedImage encoded = encodeWebpVariant(image, maxDimension, webpQuality, resizeMode);
            String generatedObjectKey = variantObjectKey(entity.getObjectKey(), variant, WEBP_FORMAT);
            try (InputStream inputStream = new ByteArrayInputStream(encoded.bytes())) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(properties.minio().bucket())
                        .object(generatedObjectKey)
                        .stream(inputStream, encoded.bytes().length, -1)
                        .contentType(encoded.contentType())
                        .build());
            }
            return new AssetContent(entity, new ByteArrayInputStream(encoded.bytes()), encoded.contentType());
        } catch (Exception exception) {
            return null;
        }
    }

    private EncodedImage encodeWebpVariant(
            BufferedImage source,
            int maxDimension,
            float webpQuality,
            ResizeMode resizeMode
    ) throws IOException {
        BufferedImage resized = switch (resizeMode) {
            case MAX_SIDE -> resizeToMaxSide(source, maxDimension);
            case MAX_WIDTH -> resizeToMaxWidth(source, maxDimension);
        };
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        writeCompressedImage(resized, WEBP_FORMAT, outputStream, webpQuality);
        return new EncodedImage(outputStream.toByteArray(), WEBP_CONTENT_TYPE);
    }

    private EncodedImage encodeJpegOrPngVariant(BufferedImage source, int maxSide, float jpegQuality) throws IOException {
        BufferedImage resized = resizeToMaxSide(source, maxSide);
        boolean hasAlpha = resized.getColorModel().hasAlpha();
        String format = hasAlpha ? "png" : "jpg";
        String contentType = hasAlpha ? "image/png" : "image/jpeg";
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        if (hasAlpha) {
            ImageIO.write(resized, format, outputStream);
        } else {
            writeJpeg(resized, outputStream, jpegQuality);
        }

        return new EncodedImage(outputStream.toByteArray(), contentType);
    }

    private BufferedImage resizeToMaxSide(BufferedImage source, int maxSide) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int longestSide = Math.max(sourceWidth, sourceHeight);
        if (longestSide <= 0) return source;

        double scale = Math.min(1.0, (double) maxSide / longestSide);
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        return resizeToDimensions(source, targetWidth, targetHeight);
    }

    private BufferedImage resizeToMaxWidth(BufferedImage source, int maxWidth) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0) return source;

        double scale = Math.min(1.0, (double) maxWidth / sourceWidth);
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        return resizeToDimensions(source, targetWidth, targetHeight);
    }

    private BufferedImage resizeToDimensions(BufferedImage source, int targetWidth, int targetHeight) {
        int imageType = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, imageType);
        Graphics2D graphics = target.createGraphics();
        try {
            if (!source.getColorModel().hasAlpha()) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private void writeJpeg(BufferedImage image, ByteArrayOutputStream outputStream, float quality) throws IOException {
        writeCompressedImage(image, "jpg", outputStream, quality);
    }

    private void writeCompressedImage(
            BufferedImage image,
            String format,
            ByteArrayOutputStream outputStream,
            float quality
    ) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            if (!ImageIO.write(image, format, outputStream)) {
                throw new IOException("缺少 %s 图片编码器".formatted(format));
            }
            return;
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] compressionTypes = writeParam.getCompressionTypes();
                if (compressionTypes != null && compressionTypes.length > 0) {
                    writeParam.setCompressionType(preferredCompressionType(compressionTypes));
                }
                writeParam.setCompressionQuality(quality);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
        } finally {
            writer.dispose();
        }
    }

    private String preferredCompressionType(String[] compressionTypes) {
        for (String compressionType : compressionTypes) {
            if ("Lossy".equalsIgnoreCase(compressionType) || "JPEG".equalsIgnoreCase(compressionType)) {
                return compressionType;
            }
        }
        return compressionTypes[0];
    }

    private String variantObjectKey(String objectKey, String variant, String extension) {
        return "%s.%s.%s".formatted(objectKey, variant, extension);
    }

    private String variantContentType(String objectKey) {
        if (objectKey.endsWith(".webp")) return WEBP_CONTENT_TYPE;
        return objectKey.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    private String previewUrlPath(UUID assetId) {
        return "/api/assets/%s/preview".formatted(assetId);
    }

    private String thumbnailUrlPath(UUID assetId) {
        return "/api/assets/%s/thumbnail".formatted(assetId);
    }

    public record AssetContent(AssetEntity entity, InputStream stream, String contentType) {
    }

    public record PreparedImageContent(byte[] bytes, String contentType) {
    }

    private record ImageSize(Integer width, Integer height) {
    }

    private record EncodedImage(byte[] bytes, String contentType) {
    }

    private enum ResizeMode {
        MAX_SIDE,
        MAX_WIDTH
    }
}
