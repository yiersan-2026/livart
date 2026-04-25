package com.artisanlab.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiProxyDtos {
    private AiProxyDtos() {
    }

    public record ImageReferenceCandidate(
            @NotBlank @Size(max = 120) String id,
            @Size(max = 200) String name,
            Integer index,
            Integer width,
            Integer height
    ) {
    }

    public record ImageReferenceAnalysisRequest(
            @NotBlank @Size(max = 4000) String prompt,
            @Size(max = 120) String contextImageId,
            @Valid
            @NotEmpty
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images
    ) {
    }

    public record ImageReferenceAnalysisResponse(
            String baseImageId,
            List<String> referenceImageIds,
            String reason,
            String source
    ) {
    }
}
