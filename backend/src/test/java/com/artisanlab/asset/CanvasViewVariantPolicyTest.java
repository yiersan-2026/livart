package com.artisanlab.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasViewVariantPolicyTest {
    @ParameterizedTest
    @CsvSource({
            "0, 512",
            "1, 512",
            "512, 512",
            "513, 1024",
            "1024, 1024",
            "1025, 2048",
            "4096, 2048"
    })
    void normalizeWidthPicksTheSmallestAvailableWidthTier(int requestedWidth, int expectedWidth) {
        assertThat(CanvasViewVariantPolicy.normalizeWidth(requestedWidth)).isEqualTo(expectedWidth);
    }

    @Test
    void variantNameUsesWidthSpecificCacheKey() {
        assertThat(CanvasViewVariantPolicy.variantName(800)).isEqualTo("view-w-1024");
    }
}
