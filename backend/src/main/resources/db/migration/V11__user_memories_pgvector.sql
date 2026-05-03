CREATE TABLE IF NOT EXISTS artisan_user_memories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES artisan_users(id) ON DELETE CASCADE,
    slot_key VARCHAR(80) NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary TEXT NOT NULL,
    value_text TEXT NOT NULL,
    source_text TEXT NOT NULL DEFAULT '',
    confidence VARCHAR(20) NOT NULL DEFAULT 'medium',
    importance INTEGER NOT NULL DEFAULT 50,
    sticky BOOLEAN NOT NULL DEFAULT FALSE,
    content_hash VARCHAR(80) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    hit_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_artisan_user_memories_user_slot UNIQUE (user_id, slot_key)
);

CREATE INDEX IF NOT EXISTS idx_artisan_user_memories_user_updated
    ON artisan_user_memories(user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_artisan_user_memories_user_sticky
    ON artisan_user_memories(user_id, sticky DESC, updated_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        EXECUTE 'ALTER TABLE artisan_user_memories ADD COLUMN IF NOT EXISTS embedding vector';
        BEGIN
            EXECUTE 'CREATE INDEX IF NOT EXISTS idx_artisan_user_memories_embedding
                     ON artisan_user_memories USING hnsw (embedding vector_cosine_ops)
                     WHERE embedding IS NOT NULL';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'user memory pgvector index creation skipped: %', SQLERRM;
        END;
    END IF;
END $$;
