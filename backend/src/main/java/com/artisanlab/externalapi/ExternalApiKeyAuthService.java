package com.artisanlab.externalapi;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@Service
public class ExternalApiKeyAuthService {
    private static final String DEFAULT_HEADER_NAME = "X-Livart-Api-Key";

    private final ArtisanProperties properties;

    public ExternalApiKeyAuthService(ArtisanProperties properties) {
        this.properties = properties;
    }

    public UUID requireAuthorizedOwner(HttpServletRequest request) {
        if (!properties.externalApi().enabled() || configuredApiKeys().isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_API_DISABLED", "外部生图接口暂未启用");
        }

        String providedApiKey = normalizedHeaderValue(request.getHeader(headerName()));
        if (providedApiKey.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "EXTERNAL_API_KEY_REQUIRED", "请提供有效的 API Key");
        }

        for (String configuredApiKey : configuredApiKeys()) {
            if (secureEquals(configuredApiKey, providedApiKey)) {
                return ownerIdForApiKey(configuredApiKey);
            }
        }

        throw new ApiException(HttpStatus.UNAUTHORIZED, "EXTERNAL_API_KEY_INVALID", "API Key 无效");
    }

    UUID ownerIdForApiKey(String apiKey) {
        return UUID.nameUUIDFromBytes(("livart-external-api:" + (apiKey == null ? "" : apiKey.trim()))
                .getBytes(StandardCharsets.UTF_8));
    }

    private String headerName() {
        String configuredName = properties.externalApi().headerName();
        return configuredName == null || configuredName.isBlank() ? DEFAULT_HEADER_NAME : configuredName.trim();
    }

    private List<String> configuredApiKeys() {
        String rawApiKeys = properties.externalApi().apiKeys();
        if (rawApiKeys == null || rawApiKeys.isBlank()) {
            return List.of();
        }

        return java.util.Arrays.stream(rawApiKeys.split("[,\\n\\r]+"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalizedHeaderValue(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean secureEquals(String left, String right) {
        return MessageDigest.isEqual(
                (left == null ? "" : left).getBytes(StandardCharsets.UTF_8),
                (right == null ? "" : right).getBytes(StandardCharsets.UTF_8)
        );
    }
}
