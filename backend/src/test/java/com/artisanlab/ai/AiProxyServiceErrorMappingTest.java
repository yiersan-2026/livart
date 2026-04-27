package com.artisanlab.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProxyServiceErrorMappingTest {
    @Test
    void wrapsImageUpstream502AsContentPolicyMessage() {
        AiProxyService.UserFacingImageJobError error = AiProxyService.toUserFacingImageJobError(
                502,
                "EOF reached while reading",
                "",
                ""
        );

        assertThat(error.message()).contains("可能").contains("安全策略");
        assertThat(error.code()).isEqualTo("POSSIBLE_CONTENT_POLICY_BLOCKED");
        assertThat(error.type()).isEqualTo("content_policy");
        assertThat(error.hideUpstreamPayload()).isTrue();
    }

    @Test
    void keepsExplicitNonPolicyErrorsReadable() {
        AiProxyService.UserFacingImageJobError error = AiProxyService.toUserFacingImageJobError(
                400,
                "image size is too large",
                "invalid_request_error",
                "invalid_request_error"
        );

        assertThat(error.message()).isEqualTo("image size is too large");
        assertThat(error.code()).isEqualTo("invalid_request_error");
        assertThat(error.type()).isEqualTo("invalid_request_error");
        assertThat(error.hideUpstreamPayload()).isFalse();
    }

    @Test
    void wrapsPolicyTextRegardlessOfStatusCode() {
        AiProxyService.UserFacingImageJobError error = AiProxyService.toUserFacingImageJobError(
                400,
                "Your request was rejected by the safety policy",
                "",
                ""
        );

        assertThat(error.message()).contains("安全策略");
        assertThat(error.code()).isEqualTo("POSSIBLE_CONTENT_POLICY_BLOCKED");
        assertThat(error.type()).isEqualTo("content_policy");
        assertThat(error.hideUpstreamPayload()).isTrue();
    }
}
