package com.artisanlab.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProxyServiceImageSizeTest {
    @ParameterizedTest
    @CsvSource({
            "小猫抓蝴蝶, 2048x2048",
            "画幅比例要求：最终输出为 1:1 方图构图, 2048x2048",
            "画幅比例要求：最终输出为 16:9 横向宽屏构图, 2048x1152",
            "画幅比例要求：最终输出为 9:16 竖向手机屏幕构图, 1152x2048",
            "完整球形全景 2:1 equirectangular panorama, 2048x1024",
            "画幅比例要求：最终输出为 4:3 横向标准构图, 2048x1536",
            "画幅比例要求：最终输出为 3:4 竖向标准构图, 1536x2048"
    })
    void resolvesDefault2kSizeFromPromptAspectRatio(String prompt, String expectedSize) {
        assertThat(AiProxyService.resolveDefaultTextToImageSize(prompt, 2048)).isEqualTo(expectedSize);
    }

    @Test
    void defaultImageJobWorkerCountAllowsAtLeastThreeParallelImages() {
        assertThat(AiProxyService.resolveImageJobWorkerCount(0)).isGreaterThanOrEqualTo(4);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "3, 3",
            "80, 64"
    })
    void resolvesConfiguredImageJobWorkerCount(int configured, int expected) {
        assertThat(AiProxyService.resolveImageJobWorkerCount(configured)).isEqualTo(expected);
    }
}
