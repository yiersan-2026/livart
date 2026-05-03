package com.artisanlab.memory;

public record UserMemorySearchResult(
        String id,
        String slotKey,
        String title,
        String summary,
        String valueText,
        String confidence,
        int importance,
        boolean sticky,
        double score
) {
}
