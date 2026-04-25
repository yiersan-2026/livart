package com.artisanlab.canvas;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CanvasSaveMessage(
        UUID messageId,
        UUID userId,
        UUID canvasId,
        String title,
        String stateJson,
        long revision,
        OffsetDateTime requestedAt
) {
}
