package com.artisanlab.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class CanvasDtos {
    private CanvasDtos() {
    }

    public record SaveCanvasRequest(
            String title,
            @NotNull JsonNode state,
            Long clientRevision
    ) {
    }

    public record CreateCanvasRequest(
            String title
    ) {
    }

    public record CanvasSummary(
            UUID id,
            String title,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long revision
    ) {
    }

    public record CanvasResponse(
            UUID id,
            String title,
            JsonNode state,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long revision,
            boolean queued
    ) {
    }
}
