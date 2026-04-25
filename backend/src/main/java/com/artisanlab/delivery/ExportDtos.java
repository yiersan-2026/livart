package com.artisanlab.delivery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ExportDtos {
    private ExportDtos() {
    }

    public record ImageExportItemRequest(
            @NotNull UUID assetId,
            String filename
    ) {
    }

    public record ImageExportRequest(
            @Valid
            @NotEmpty
            @Size(max = 100)
            List<ImageExportItemRequest> images,
            String filename
    ) {
    }

    public record ExportResponse(
            UUID exportId,
            String filename,
            String downloadUrl,
            OffsetDateTime expiresAt
    ) {
    }
}
