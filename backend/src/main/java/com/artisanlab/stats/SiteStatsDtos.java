package com.artisanlab.stats;

public final class SiteStatsDtos {
    private SiteStatsDtos() {
    }

    public record Overview(
            long userCount,
            long generatedImageCount,
            long activeImageJobCount,
            Memory memory,
            Processor processor,
            Disk disk
    ) {
    }

    public record Memory(
            long usedBytes,
            long freeBytes,
            long totalBytes,
            double usedPercent
    ) {
    }

    public record Processor(
            double usedPercent,
            int availableProcessors
    ) {
    }

    public record Disk(
            String path,
            long usedBytes,
            long freeBytes,
            long totalBytes,
            double usedPercent
    ) {
    }
}
