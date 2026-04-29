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

    @Test
    void appendsViewChangeGazeLockAfterOptimizerOutput() {
        String guardedPrompt = AiProxyService.appendViewChangeGazeConstraints(
                "以原图为完整三维场景，将观察点移动到右侧 60 度。"
        );

        assertThat(guardedPrompt).contains(
                "不应继续直视当前画面",
                "角色视线仍指向原始相机位置",
                "direct eye contact with the new camera",
                "保持原图镜头焦段",
                "禁止扩大视野",
                "禁止把特写变成近景、中景或远景"
        );
    }

    @Test
    void appendsViewChangeFramingLockEvenWhenOptimizerAlreadyContainsGazeLock() {
        String guardedPrompt = AiProxyService.appendViewChangeGazeConstraints(
                "以原图为完整三维场景，将观察点移动到右侧 60 度，禁止 direct eye contact with the new camera。"
        );

        assertThat(guardedPrompt).contains(
                "direct eye contact with the new camera",
                "禁止扩大视野",
                "主体占画面比例"
        );
    }
}
