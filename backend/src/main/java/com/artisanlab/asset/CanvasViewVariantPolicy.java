package com.artisanlab.asset;

import java.util.List;

final class CanvasViewVariantPolicy {
    static final int SMALL_WIDTH = 512;
    static final int MEDIUM_WIDTH = 1024;
    static final int LARGE_WIDTH = 2048;
    static final List<Integer> WIDTH_TIERS = List.of(SMALL_WIDTH, MEDIUM_WIDTH, LARGE_WIDTH);

    private CanvasViewVariantPolicy() {
    }

    static int normalizeWidth(int requestedWidth) {
        if (requestedWidth <= SMALL_WIDTH) {
            return SMALL_WIDTH;
        }
        if (requestedWidth <= MEDIUM_WIDTH) {
            return MEDIUM_WIDTH;
        }
        return LARGE_WIDTH;
    }

    static String variantName(int requestedWidth) {
        return "view-w-%d".formatted(normalizeWidth(requestedWidth));
    }
}
