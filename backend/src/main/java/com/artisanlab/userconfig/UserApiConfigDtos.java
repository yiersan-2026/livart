package com.artisanlab.userconfig;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class UserApiConfigDtos {
    private UserApiConfigDtos() {
    }

    public record SaveRequest(
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 4000) String apiKey,
            @NotBlank @Size(max = 120) String model,
            @NotBlank @Size(max = 120) String chatModel
    ) {
    }

    public record Response(
            String baseUrl,
            String apiKey,
            String model,
            String chatModel,
            String textToImageUrl,
            String imageToImageUrl,
            OffsetDateTime updatedAt,
            boolean serverDefault
    ) {
    }
}
