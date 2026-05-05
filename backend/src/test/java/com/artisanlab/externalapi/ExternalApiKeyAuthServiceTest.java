package com.artisanlab.externalapi;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalApiKeyAuthServiceTest {
    @Test
    void authenticatesConfiguredApiKeyAndBuildsStableOwnerId() {
        ExternalApiKeyAuthService service = new ExternalApiKeyAuthService(properties("key-a,key-b"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Livart-Api-Key", "key-b");

        UUID ownerId = service.requireAuthorizedOwner(request);

        assertThat(ownerId).isEqualTo(service.ownerIdForApiKey("key-b"));
        assertThat(ownerId).isNotEqualTo(service.ownerIdForApiKey("key-a"));
    }

    @Test
    void rejectsMissingApiKeyHeader() {
        ExternalApiKeyAuthService service = new ExternalApiKeyAuthService(properties("key-a"));

        assertThatThrownBy(() -> service.requireAuthorizedOwner(new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void rejectsInvalidApiKey() {
        ExternalApiKeyAuthService service = new ExternalApiKeyAuthService(properties("key-a"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Livart-Api-Key", "wrong-key");

        assertThatThrownBy(() -> service.requireAuthorizedOwner(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("API Key");
    }

    private ArtisanProperties properties(String apiKeys) {
        return new ArtisanProperties(
                new ArtisanProperties.Auth("test-secret-at-least-long-enough", 7),
                new ArtisanProperties.Canvas(UUID.randomUUID()),
                new ArtisanProperties.Cors("*"),
                new ArtisanProperties.Minio("http://localhost:9000", "minio", "minio123", "bucket"),
                new ArtisanProperties.Rabbitmq("canvas-save", "canvas-save-dlq"),
                new ArtisanProperties.ExternalImages("https://example.com/api/images", "", 30),
                new ArtisanProperties.ExternalApi(true, "X-Livart-Api-Key", apiKeys)
        );
    }
}
