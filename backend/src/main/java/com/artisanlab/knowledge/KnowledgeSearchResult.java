package com.artisanlab.knowledge;

public record KnowledgeSearchResult(
        String id,
        String docSlug,
        String title,
        String content,
        double score
) {
}
