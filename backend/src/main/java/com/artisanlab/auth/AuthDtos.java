package com.artisanlab.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Size(min = 6, max = 120) String password,
            @Size(max = 120) String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    public record AuthUser(
            UUID id,
            String username,
            String displayName,
            OffsetDateTime createdAt
    ) {
    }

    public record AuthResponse(
            AuthUser user,
            String token,
            OffsetDateTime expiresAt
    ) {
    }
}
