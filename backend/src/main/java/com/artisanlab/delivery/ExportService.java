package com.artisanlab.delivery;

import com.artisanlab.asset.AssetEntity;
import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {
    private static final Duration EXPORT_TTL = Duration.ofHours(24);
    private static final String EXPORT_FILE_SUFFIX = ".zip";
    private static final String FILENAME_META_SUFFIX = ".name";

    private final AssetService assetService;
    private final Path exportRoot;

    public ExportService(AssetService assetService) {
        this.assetService = assetService;
        this.exportRoot = Path.of(System.getProperty("java.io.tmpdir"), "livart-exports");
    }

    public ExportDtos.ExportResponse createImageExport(UUID userId, ExportDtos.ImageExportRequest request) {
        List<ExportDtos.ImageExportItemRequest> images = request.images();
        if (images == null || images.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_EXPORT", "请选择要下载的成品图片");
        }

        UUID exportId = UUID.randomUUID();
        String zipFilename = buildZipFilename(request.filename(), images.size());
        Path userDirectory = userDirectory(userId);
        Path zipPath = exportPath(userId, exportId);
        Path filenamePath = filenamePath(userId, exportId);

        try {
            Files.createDirectories(userDirectory);
            try (
                    OutputStream fileOutputStream = Files.newOutputStream(zipPath);
                    ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream, StandardCharsets.UTF_8)
            ) {
                Set<String> usedEntryNames = new HashSet<>();
                for (ExportDtos.ImageExportItemRequest imageRequest : images) {
                    AssetService.AssetContent assetContent = assetService.getContentForUser(userId, imageRequest.assetId());
                    AssetEntity asset = assetContent.entity();
                    String imageFilename = uniqueZipEntryFilename(
                            buildImageFilename(imageRequest.filename(), asset),
                            usedEntryNames
                    );

                    try (InputStream inputStream = assetContent.stream()) {
                        ZipEntry zipEntry = new ZipEntry(imageFilename);
                        zipOutputStream.putNextEntry(zipEntry);
                        inputStream.transferTo(zipOutputStream);
                        zipOutputStream.closeEntry();
                    }
                }
            }
            Files.writeString(filenamePath, zipFilename, StandardCharsets.UTF_8);
        } catch (ApiException exception) {
            deleteQuietly(zipPath);
            deleteQuietly(filenamePath);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(zipPath);
            deleteQuietly(filenamePath);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_CREATE_FAILED", "生成下载压缩包失败");
        }

        return new ExportDtos.ExportResponse(
                exportId,
                zipFilename,
                "/api/exports/%s/download".formatted(exportId),
                OffsetDateTime.now(ZoneOffset.UTC).plus(EXPORT_TTL)
        );
    }

    public ExportFile getExportFile(UUID userId, UUID exportId) {
        Path zipPath = exportPath(userId, exportId);
        if (!Files.isRegularFile(zipPath)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "压缩包不存在或已被清理");
        }

        return new ExportFile(zipPath, readExportFilename(userId, exportId));
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void cleanupExpiredExports() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(EXPORT_TTL);
        cleanupExportsOlderThan(cutoff);
    }

    private void cleanupExportsOlderThan(OffsetDateTime cutoff) {
        if (!Files.isDirectory(exportRoot)) return;

        try (Stream<Path> paths = Files.walk(exportRoot)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXPORT_FILE_SUFFIX)
                            || path.getFileName().toString().endsWith(FILENAME_META_SUFFIX))
                    .filter(path -> isOlderThan(path, cutoff))
                    .forEach(this::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    private boolean isOlderThan(Path path, OffsetDateTime cutoff) {
        try {
            OffsetDateTime lastModified = OffsetDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneOffset.UTC);
            return lastModified.isBefore(cutoff);
        } catch (IOException ignored) {
            return false;
        }
    }

    private Path userDirectory(UUID userId) {
        return exportRoot.resolve(userId.toString());
    }

    private Path exportPath(UUID userId, UUID exportId) {
        return userDirectory(userId).resolve(exportId + EXPORT_FILE_SUFFIX);
    }

    private Path filenamePath(UUID userId, UUID exportId) {
        return userDirectory(userId).resolve(exportId + FILENAME_META_SUFFIX);
    }

    private String readExportFilename(UUID userId, UUID exportId) {
        Path filenamePath = filenamePath(userId, exportId);
        if (!Files.isRegularFile(filenamePath)) {
            return exportId + EXPORT_FILE_SUFFIX;
        }

        try {
            return sanitizeFilename(Files.readString(filenamePath, StandardCharsets.UTF_8), exportId + EXPORT_FILE_SUFFIX);
        } catch (IOException ignored) {
            return exportId + EXPORT_FILE_SUFFIX;
        }
    }

    private String buildZipFilename(String requestedFilename, int imageCount) {
        String fallback = imageCount == 1
                ? "livart-image" + EXPORT_FILE_SUFFIX
                : "livart-images" + EXPORT_FILE_SUFFIX;
        String filename = sanitizeFilename(requestedFilename, fallback);
        return filename.toLowerCase(Locale.ROOT).endsWith(EXPORT_FILE_SUFFIX)
                ? filename
                : stripExtension(filename) + EXPORT_FILE_SUFFIX;
    }

    private String buildImageFilename(String requestedFilename, AssetEntity asset) {
        String fallbackName = StringUtils.hasText(asset.getOriginalFilename())
                ? asset.getOriginalFilename()
                : asset.getId().toString() + extensionForContentType(asset.getMimeType());
        String filename = sanitizeFilename(requestedFilename, fallbackName);
        if (!filename.contains(".")) {
            filename += extensionForContentType(asset.getMimeType());
        }
        return filename;
    }

    private String uniqueZipEntryFilename(String filename, Set<String> usedEntryNames) {
        String candidate = filename;
        int suffix = 2;
        while (usedEntryNames.contains(candidate)) {
            candidate = stripExtension(filename) + "-" + suffix + extensionOf(filename);
            suffix += 1;
        }
        usedEntryNames.add(candidate);
        return candidate;
    }

    private String sanitizeFilename(String value, String fallback) {
        String candidate = StringUtils.hasText(value) ? value.trim() : fallback;
        candidate = candidate.replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", "-");
        candidate = candidate.replaceAll("^-+", "").replaceAll("-+$", "");
        return StringUtils.hasText(candidate) ? candidate : fallback;
    }

    private String stripExtension(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
    }

    private String extensionOf(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(extensionIndex) : "";
    }

    private String extensionForContentType(String contentType) {
        if (contentType == null) return ".png";
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public record ExportFile(Path path, String filename) {
    }
}
