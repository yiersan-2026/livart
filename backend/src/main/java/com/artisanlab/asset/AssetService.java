package com.artisanlab.asset;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssetService {
    private final AssetMapper assetMapper;
    private final ArtisanProperties properties;
    private final MinioClient minioClient;

    public AssetService(AssetMapper assetMapper, ArtisanProperties properties) {
        this.assetMapper = assetMapper;
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.minio().endpoint())
                .credentials(properties.minio().accessKey(), properties.minio().secretKey())
                .build();
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
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
    public AssetDtos.AssetResponse upload(UUID canvasId, MultipartFile file) {
        validateImage(file);

        UUID assetId = UUID.randomUUID();
        String mimeType = normalizeMimeType(file.getContentType());
        String filename = normalizeFilename(file.getOriginalFilename());
        String objectKey = "canvases/%s/%s%s".formatted(
                canvasId == null ? "default" : canvasId,
                assetId,
                extensionFor(filename, mimeType)
        );

        ImageSize imageSize = readImageSize(file);

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(mimeType)
                    .build());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSET_UPLOAD_FAILED", "图片上传到 MinIO 失败");
        }

        AssetEntity entity = new AssetEntity();
        entity.setId(assetId);
        entity.setCanvasId(canvasId);
        entity.setObjectKey(objectKey);
        entity.setUrlPath("/api/assets/%s/content".formatted(assetId));
        entity.setOriginalFilename(filename);
        entity.setMimeType(mimeType);
        entity.setSizeBytes(file.getSize());
        entity.setWidth(imageSize.width());
        entity.setHeight(imageSize.height());
        assetMapper.insertAsset(entity);

        return toResponse(assetMapper.findById(assetId));
    }

    public AssetContent getContent(UUID assetId) {
        AssetEntity entity = assetMapper.findById(assetId);
        if (entity == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片资源不存在");
        }

        try {
            GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.minio().bucket())
                    .object(entity.getObjectKey())
                    .build());
            return new AssetContent(entity, object);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSET_READ_FAILED", "读取图片资源失败");
        }
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

    private ImageSize readImageSize(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                return new ImageSize(null, null);
            }
            return new ImageSize(image.getWidth(), image.getHeight());
        } catch (IOException exception) {
            return new ImageSize(null, null);
        }
    }

    private AssetDtos.AssetResponse toResponse(AssetEntity entity) {
        return new AssetDtos.AssetResponse(
                entity.getId(),
                entity.getCanvasId(),
                entity.getUrlPath(),
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

    public record AssetContent(AssetEntity entity, InputStream stream) {
    }

    private record ImageSize(Integer width, Integer height) {
    }
}
