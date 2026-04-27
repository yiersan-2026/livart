package com.artisanlab.stats;

public final class SiteStatsDtos {
    private SiteStatsDtos() {
    }

    public record Overview(
            long userCount,
            long generatedImageCount
    ) {
    }
}
