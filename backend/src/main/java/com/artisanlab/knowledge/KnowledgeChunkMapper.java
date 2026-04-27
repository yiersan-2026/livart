package com.artisanlab.knowledge;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface KnowledgeChunkMapper {
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'artisan_knowledge_chunks'
                  AND column_name = 'embedding'
            )
            """)
    boolean hasVectorColumn();

    @Select("""
            SELECT content_hash
            FROM artisan_knowledge_docs
            WHERE slug = #{slug}
            """)
    String findDocContentHashBySlug(@Param("slug") String slug);

    @Insert("""
            INSERT INTO artisan_knowledge_docs (id, slug, title, source_path, content_hash, created_at, updated_at)
            VALUES (#{id}, #{slug}, #{title}, #{sourcePath}, #{contentHash}, NOW(), NOW())
            ON CONFLICT (slug) DO UPDATE SET
                title = EXCLUDED.title,
                source_path = EXCLUDED.source_path,
                content_hash = EXCLUDED.content_hash,
                updated_at = NOW()
            """)
    void upsertDoc(KnowledgeDocEntity entity);

    @Delete("""
            DELETE FROM artisan_knowledge_chunks
            WHERE doc_id = #{docId}
            """)
    void deleteChunksByDocId(@Param("docId") UUID docId);

    @Insert("""
            INSERT INTO artisan_knowledge_chunks (
                id, doc_id, doc_slug, chunk_index, title, content, content_hash, metadata, created_at, updated_at
            )
            VALUES (
                #{id}, #{docId}, #{docSlug}, #{chunkIndex}, #{title}, #{content}, #{contentHash},
                CAST(#{metadataJson} AS jsonb), NOW(), NOW()
            )
            ON CONFLICT (doc_id, chunk_index) DO UPDATE SET
                title = EXCLUDED.title,
                content = EXCLUDED.content,
                content_hash = EXCLUDED.content_hash,
                metadata = EXCLUDED.metadata,
                updated_at = NOW()
            """)
    void upsertChunk(KnowledgeChunkEntity entity);

    @Select("""
            SELECT id::text AS id,
                   doc_slug AS docSlug,
                   title,
                   content,
                   1 - (embedding <=> CAST(#{embedding} AS vector)) AS score
            FROM artisan_knowledge_chunks
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> CAST(#{embedding} AS vector)
            LIMIT #{limit}
            """)
    List<KnowledgeSearchResult> searchByVector(@Param("embedding") String embedding, @Param("limit") int limit);

    @Select("""
            SELECT id::text AS id,
                   doc_slug AS docSlug,
                   title,
                   content,
                   CASE
                       WHEN title ILIKE CONCAT('%', #{query}, '%') THEN 0.6
                       WHEN content ILIKE CONCAT('%', #{query}, '%') THEN 0.4
                       ELSE 0.1
                   END AS score
            FROM artisan_knowledge_chunks
            WHERE title ILIKE CONCAT('%', #{query}, '%')
               OR content ILIKE CONCAT('%', #{query}, '%')
            ORDER BY score DESC, updated_at DESC
            LIMIT #{limit}
            """)
    List<KnowledgeSearchResult> searchByText(@Param("query") String query, @Param("limit") int limit);

    @Select("""
            SELECT embedding_text
            FROM artisan_knowledge_query_embeddings
            WHERE embedding_model = #{model}
              AND question_hash = #{questionHash}
            """)
    String findCachedQueryEmbedding(
            @Param("model") String model,
            @Param("questionHash") String questionHash
    );

    @Update("""
            UPDATE artisan_knowledge_query_embeddings
            SET hit_count = hit_count + 1,
                last_used_at = NOW(),
                updated_at = NOW()
            WHERE embedding_model = #{model}
              AND question_hash = #{questionHash}
            """)
    void touchCachedQueryEmbedding(
            @Param("model") String model,
            @Param("questionHash") String questionHash
    );

    @Insert("""
            INSERT INTO artisan_knowledge_query_embeddings (
                embedding_model, question_hash, normalized_question, embedding_text, hit_count, created_at, updated_at, last_used_at
            )
            VALUES (
                #{model}, #{questionHash}, #{normalizedQuestion}, #{embedding}, 0, NOW(), NOW(), NOW()
            )
            ON CONFLICT (embedding_model, question_hash) DO UPDATE SET
                normalized_question = EXCLUDED.normalized_question,
                embedding_text = EXCLUDED.embedding_text,
                updated_at = NOW()
            """)
    void upsertCachedQueryEmbedding(
            @Param("model") String model,
            @Param("questionHash") String questionHash,
            @Param("normalizedQuestion") String normalizedQuestion,
            @Param("embedding") String embedding
    );

    @Select("""
            SELECT id,
                   doc_id,
                   doc_slug,
                   chunk_index,
                   title,
                   content,
                   content_hash,
                   metadata::text AS metadataJson
            FROM artisan_knowledge_chunks
            WHERE embedding_text IS NULL
            ORDER BY updated_at DESC
            LIMIT #{limit}
            """)
    List<KnowledgeChunkEntity> listChunksMissingEmbedding(@Param("limit") int limit);

    @Update("""
            UPDATE artisan_knowledge_chunks
            SET embedding_text = #{embedding}, updated_at = NOW()
            WHERE id = #{id}
            """)
    void updateEmbeddingText(@Param("id") UUID id, @Param("embedding") String embedding);

    @Update("""
            UPDATE artisan_knowledge_chunks
            SET embedding = CAST(#{embedding} AS vector), updated_at = NOW()
            WHERE id = #{id}
            """)
    void updateVectorEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    @Update("""
            UPDATE artisan_knowledge_chunks
            SET embedding = CAST(embedding_text AS vector), updated_at = NOW()
            WHERE id IN (
                SELECT id
                FROM artisan_knowledge_chunks
                WHERE embedding_text IS NOT NULL
                  AND embedding IS NULL
                ORDER BY updated_at DESC
                LIMIT #{limit}
            )
            """)
    int backfillVectorEmbeddingsFromText(@Param("limit") int limit);
}
