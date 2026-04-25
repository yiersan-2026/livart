package com.artisanlab.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface AssetMapper extends BaseMapper<AssetEntity> {
    @Insert("""
            INSERT INTO artisan_assets (
                id, canvas_id, object_key, url_path, original_filename,
                mime_type, size_bytes, width, height, created_at
            )
            VALUES (
                #{id}, #{canvasId}, #{objectKey}, #{urlPath}, #{originalFilename},
                #{mimeType}, #{sizeBytes}, #{width}, #{height}, NOW()
            )
            """)
    void insertAsset(AssetEntity entity);

    @Select("""
            SELECT id, canvas_id, object_key, url_path, original_filename,
                   mime_type, size_bytes, width, height, created_at
            FROM artisan_assets
            WHERE id = #{id}
            """)
    AssetEntity findById(@Param("id") UUID id);
}
