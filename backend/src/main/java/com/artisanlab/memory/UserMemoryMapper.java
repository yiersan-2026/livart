package com.artisanlab.memory;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface UserMemoryMapper {
    @Insert("""
            INSERT INTO artisan_user_memories (
                id, user_id, slot_key, title, summary, value_text, source_text, confidence,
                importance, sticky, content_hash, metadata, embedding_text, created_at, updated_at, last_used_at, hit_count
            )
            VALUES (
                #{id}, #{userId}, #{slotKey}, #{title}, #{summary}, #{valueText}, #{sourceText}, #{confidence},
                #{importance}, #{sticky}, #{contentHash}, CAST(#{metadataJson} AS jsonb), #{embeddingText}, NOW(), NOW(), NOW(), 0
            )
            ON CONFLICT (user_id, slot_key) DO UPDATE SET
                title = EXCLUDED.title,
                summary = EXCLUDED.summary,
                value_text = EXCLUDED.value_text,
                source_text = EXCLUDED.source_text,
                confidence = EXCLUDED.confidence,
                importance = EXCLUDED.importance,
                sticky = EXCLUDED.sticky,
                content_hash = EXCLUDED.content_hash,
                metadata = EXCLUDED.metadata,
                embedding_text = EXCLUDED.embedding_text,
                updated_at = NOW()
            """)
    void upsertMemory(UserMemoryEntity entity);

    @Update("""
            UPDATE artisan_user_memories
            SET embedding = CAST(#{embedding} AS vector),
                updated_at = NOW()
            WHERE user_id = #{userId}
              AND slot_key = #{slotKey}
            """)
    void updateVectorEmbedding(
            @Param("userId") UUID userId,
            @Param("slotKey") String slotKey,
            @Param("embedding") String embedding
    );

    @Select("""
            SELECT id::text AS id,
                   slot_key AS slotKey,
                   title,
                   summary,
                   value_text AS valueText,
                   confidence,
                   importance,
                   sticky,
                   1 - (embedding <=> CAST(#{embedding} AS vector)) AS score
            FROM artisan_user_memories
            WHERE user_id = #{userId}
              AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(#{embedding} AS vector), sticky DESC, importance DESC, updated_at DESC
            LIMIT #{limit}
            """)
    List<UserMemorySearchResult> searchByVector(
            @Param("userId") UUID userId,
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id::text AS id,
                   slot_key AS slotKey,
                   title,
                   summary,
                   value_text AS valueText,
                   confidence,
                   importance,
                   sticky,
                   CASE
                       WHEN value_text ILIKE CONCAT('%', #{query}, '%') THEN 0.8
                       WHEN title ILIKE CONCAT('%', #{query}, '%') THEN 0.6
                       WHEN summary ILIKE CONCAT('%', #{query}, '%') THEN 0.5
                       WHEN source_text ILIKE CONCAT('%', #{query}, '%') THEN 0.3
                       ELSE 0.1
                   END AS score
            FROM artisan_user_memories
            WHERE user_id = #{userId}
              AND (
                   value_text ILIKE CONCAT('%', #{query}, '%')
                OR title ILIKE CONCAT('%', #{query}, '%')
                OR summary ILIKE CONCAT('%', #{query}, '%')
                OR source_text ILIKE CONCAT('%', #{query}, '%')
              )
            ORDER BY sticky DESC, importance DESC, updated_at DESC
            LIMIT #{limit}
            """)
    List<UserMemorySearchResult> searchByText(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id::text AS id,
                   slot_key AS slotKey,
                   title,
                   summary,
                   value_text AS valueText,
                   confidence,
                   importance,
                   sticky,
                   0.0 AS score
            FROM artisan_user_memories
            WHERE user_id = #{userId}
            ORDER BY sticky DESC, updated_at DESC, importance DESC
            LIMIT #{limit}
            """)
    List<UserMemorySearchResult> listRecentMemories(
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE artisan_user_memories
            SET hit_count = hit_count + 1,
                last_used_at = NOW(),
                updated_at = NOW()
            WHERE user_id = #{userId}
              AND slot_key = #{slotKey}
            """)
    void touchMemory(
            @Param("userId") UUID userId,
            @Param("slotKey") String slotKey
    );

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
}
