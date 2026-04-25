package com.artisanlab.canvas;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CanvasSnapshotMapper extends BaseMapper<CanvasSnapshotEntity> {
    @Insert("""
            INSERT INTO artisan_canvas_snapshots (id, canvas_id, state_json, revision, created_at)
            SELECT #{id}, #{canvasId}, CAST(#{stateJson} AS jsonb), #{revision}, NOW()
            WHERE NOT EXISTS (
                SELECT 1
                FROM artisan_canvas_snapshots
                WHERE canvas_id = #{canvasId}
                  AND created_at > NOW() - INTERVAL '30 seconds'
            )
            """)
    int insertSnapshotIfDue(CanvasSnapshotEntity entity);
}
