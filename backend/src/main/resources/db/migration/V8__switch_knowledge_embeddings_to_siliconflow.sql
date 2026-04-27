UPDATE artisan_knowledge_chunks
SET embedding_text = NULL,
    updated_at = NOW()
WHERE embedding_text IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS vector;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pgvector extension exists but could not be enabled: %', SQLERRM;
        END;
    ELSE
        RAISE NOTICE 'pgvector extension is not available; knowledge vector column will be skipped';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        EXECUTE 'ALTER TABLE artisan_knowledge_chunks ADD COLUMN IF NOT EXISTS embedding vector(1024)';
        EXECUTE 'ALTER TABLE artisan_knowledge_chunks ALTER COLUMN embedding TYPE vector(1024) USING NULL::vector(1024)';
        BEGIN
            EXECUTE 'DROP INDEX IF EXISTS idx_artisan_knowledge_chunks_embedding';
            EXECUTE 'CREATE INDEX IF NOT EXISTS idx_artisan_knowledge_chunks_embedding
                     ON artisan_knowledge_chunks USING hnsw (embedding vector_cosine_ops)
                     WHERE embedding IS NOT NULL';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pgvector index creation skipped: %', SQLERRM;
        END;
    END IF;
END $$;
