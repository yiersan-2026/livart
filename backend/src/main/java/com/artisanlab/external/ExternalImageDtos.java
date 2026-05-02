package com.artisanlab.external;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ExternalImageDtos {
    private ExternalImageDtos() {
    }

    public record SearchRequest(
            @NotBlank(message = "请输入社交媒体链接")
            @Size(max = 2000, message = "链接过长")
            String url
    ) {
    }

    public record SearchResponse(
            String sourceUrl,
            List<ImageCandidate> images
    ) {
    }

    public record ParseHistoryItem(
            String sourceUrl,
            String sourceHost,
            int imageCount,
            OffsetDateTime lastParsedAt
    ) {
    }

    public record ParseHistoryResponse(
            List<ParseHistoryItem> items
    ) {
    }

    public record ImageCandidate(
            String id,
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

    public record ImportRequest(
            @NotBlank(message = "请选择要导入的图片")
            @Size(max = 4000, message = "图片链接过长")
            String url,
            @Size(max = 255, message = "文件名过长")
            String filename,
            UUID canvasId
    ) {
    }

    public record ImportedImageResponse(
            UUID assetId,
            String urlPath,
            String previewUrlPath,
            String thumbnailUrlPath,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            OffsetDateTime createdAt,
            String sourceUrl
    ) {
    }
}
