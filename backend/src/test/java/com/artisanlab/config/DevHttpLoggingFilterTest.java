package com.artisanlab.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevHttpLoggingFilterTest {
    @Test
    void masksSecretsAndLargeImageFields() {
        String input = """
                {"apiKey":"sk-1234567890abcdef","password":"plain","image":"data:image/png;base64,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """;

        String sanitized = DevHttpLoggingFilter.sanitizeForLog(input);

        assertThat(sanitized).contains("\"apiKey\":\"***\"");
        assertThat(sanitized).contains("\"password\":\"***\"");
        assertThat(sanitized).contains("\"image\":\"<large image data omitted>\"");
        assertThat(sanitized).doesNotContain("sk-1234567890abcdef");
        assertThat(sanitized).doesNotContain("plain");
    }

    @Test
    void masksBearerTokensAndQuerySecrets() {
        String sanitized = DevHttpLoggingFilter.sanitizeForLog(
                "Authorization: Bearer token-value api_key=sk-1234567890abcdef&prompt=cat"
        );

        assertThat(sanitized).contains("Bearer ***");
        assertThat(sanitized).contains("api_key=***");
        assertThat(sanitized).contains("prompt=cat");
    }
}
