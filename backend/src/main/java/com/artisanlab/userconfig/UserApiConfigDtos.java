package com.artisanlab.userconfig;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class UserApiConfigDtos {
    public static final String MASKED_API_KEY = "";

    private UserApiConfigDtos() {
    }

    public record SaveRequest(
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 4000) String apiKey,
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
            boolean hasApiKey,
            boolean serverDefault
    ) {
    }

    public record ResolvedConfig(
            String baseUrl,
            String apiKey,
            String model,
            String chatModel,
            String textToImageUrl,
            String imageToImageUrl,
            boolean serverDefault
    ) {
    }
}
