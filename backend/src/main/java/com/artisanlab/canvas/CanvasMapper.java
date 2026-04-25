package com.artisanlab.canvas;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface CanvasMapper extends BaseMapper<CanvasEntity> {
    @Select("""
            SELECT id, user_id, title, state_json::text AS state_json, created_at, updated_at, revision
            FROM artisan_canvases
            WHERE id = #{id}
            """)
    CanvasEntity findByIdWithJson(@Param("id") UUID id);

    @Select("""
            SELECT id, user_id, title, state_json::text AS state_json, created_at, updated_at, revision
            FROM artisan_canvases
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    CanvasEntity findByIdAndUserIdWithJson(@Param("id") UUID id, @Param("userId") UUID userId);

    @Select("""
            SELECT id, user_id, title, created_at, updated_at, revision
            FROM artisan_canvases
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC, created_at DESC
            """)
    List<CanvasEntity> listSummariesByUserId(@Param("userId") UUID userId);

    @Insert("""
            INSERT INTO artisan_canvases (id, user_id, title, state_json, revision, created_at, updated_at)
            VALUES (#{id}, #{userId}, #{title}, '{}'::jsonb, 0, NOW(), NOW())
            """)
    void insertEmpty(CanvasEntity entity);

    @Insert("""
            INSERT INTO artisan_canvases (id, user_id, title, state_json, revision, created_at, updated_at)
            VALUES (#{id}, #{userId}, #{title}, CAST(#{stateJson} AS jsonb), #{revision}, NOW(), NOW())
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                state_json = EXCLUDED.state_json,
                revision = EXCLUDED.revision,
                updated_at = NOW()
            WHERE artisan_canvases.revision <= EXCLUDED.revision
              AND artisan_canvases.user_id = EXCLUDED.user_id
            """)
    int upsertIfNewer(CanvasEntity entity);
}
