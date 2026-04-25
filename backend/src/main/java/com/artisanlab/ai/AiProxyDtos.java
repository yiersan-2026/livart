package com.artisanlab.ai;

public final class AiProxyDtos {
    private AiProxyDtos() {
    }

    public record PromptOptimizeRequest(
            String prompt,
            String mode
    ) {
    }
}
