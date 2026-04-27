package com.artisanlab.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProxyServicePromptOptimizerTest {
    @Test
    void fallsBackToOriginalPromptWhenOptimizerRefuses() {
        String originalPrompt = "一只小猫在窗边看雨";

        String selectedPrompt = AiProxyService.selectPromptOptimizerOutput(
                "抱歉，我无法协助优化这个提示词。",
                originalPrompt
        );

        assertThat(selectedPrompt).isEqualTo(originalPrompt);
    }

    @Test
    void keepsOptimizerOutputWhenItIsARealPrompt() {
        String selectedPrompt = AiProxyService.selectPromptOptimizerOutput(
                "窗边的小猫望着雨夜，柔和室内光，浅景深，真实摄影质感。",
                "小猫看雨"
        );

        assertThat(selectedPrompt).contains("窗边的小猫");
    }
}
