CREATE TABLE IF NOT EXISTS artisan_knowledge_docs (
    id UUID PRIMARY KEY,
    slug VARCHAR(160) NOT NULL UNIQUE,
    title VARCHAR(240) NOT NULL,
    source_path VARCHAR(512) NOT NULL,
    content_hash VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS artisan_knowledge_chunks (
    id UUID PRIMARY KEY,
    doc_id UUID NOT NULL REFERENCES artisan_knowledge_docs(id) ON DELETE CASCADE,
    doc_slug VARCHAR(160) NOT NULL,
    chunk_index INTEGER NOT NULL,
    title VARCHAR(240) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(80) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_artisan_knowledge_chunks_doc_index UNIQUE (doc_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_chunks_doc
    ON artisan_knowledge_chunks(doc_id, chunk_index);

CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_chunks_slug
    ON artisan_knowledge_chunks(doc_slug);

CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_chunks_updated
    ON artisan_knowledge_chunks(updated_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS vector;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pgvector extension exists but could not be enabled: %', SQLERRM;
        END;
    ELSE
        RAISE NOTICE 'pgvector extension is not available; knowledge search will use keyword fallback until pgvector is installed';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        EXECUTE 'ALTER TABLE artisan_knowledge_chunks ADD COLUMN IF NOT EXISTS embedding vector';
        BEGIN
            EXECUTE 'CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_chunks_embedding
                     ON artisan_knowledge_chunks USING hnsw (embedding vector_cosine_ops)
                     WHERE embedding IS NOT NULL';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pgvector index creation skipped: %', SQLERRM;
        END;
    END IF;
END $$;
