package com.artisanlab.asset;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AssetDtos {
    private AssetDtos() {
    }

    public record AssetRotationRequest(String direction, Integer quarterTurns) {
    }

    public record AssetResponse(
            UUID id,
            UUID canvasId,
            UUID userId,
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
}
