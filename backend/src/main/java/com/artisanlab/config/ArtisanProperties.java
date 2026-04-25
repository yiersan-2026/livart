package com.artisanlab.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Validated
@ConfigurationProperties(prefix = "artisan")
public record ArtisanProperties(
        @Valid @NotNull Canvas canvas,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Minio minio,
        @Valid @NotNull Rabbitmq rabbitmq
) {
    public record Canvas(
            @NotNull UUID defaultCanvasId
    ) {
    }

    public record Cors(
            @NotBlank String allowedOrigins
    ) {
    }

    public record Minio(
            @NotBlank String endpoint,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            @NotBlank String bucket
    ) {
    }

    public record Rabbitmq(
            @NotBlank String canvasSaveQueue,
            @NotBlank String canvasSaveDeadLetterQueue
    ) {
    }
}
