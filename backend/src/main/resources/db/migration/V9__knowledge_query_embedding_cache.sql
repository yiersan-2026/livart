CREATE TABLE IF NOT EXISTS artisan_knowledge_query_embeddings (
    embedding_model VARCHAR(160) NOT NULL,
    question_hash CHAR(64) NOT NULL,
    normalized_question TEXT NOT NULL,
    embedding_text TEXT NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_artisan_knowledge_query_embeddings PRIMARY KEY (embedding_model, question_hash)
);

CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_query_embeddings_updated
    ON artisan_knowledge_query_embeddings(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_query_embeddings_last_used
    ON artisan_knowledge_query_embeddings(last_used_at DESC);
