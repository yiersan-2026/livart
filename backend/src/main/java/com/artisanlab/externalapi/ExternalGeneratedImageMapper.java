package com.artisanlab.externalapi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ExternalGeneratedImageMapper extends BaseMapper<ExternalGeneratedImageEntity> {
    @Insert("""
            INSERT INTO artisan_external_generated_images (
                id, job_id, owner_id, asset_id, label, image_index, created_at
            )
            VALUES (
                #{id}, #{jobId}, #{ownerId}, #{assetId}, #{label}, #{imageIndex}, NOW()
            )
            ON CONFLICT (job_id, image_index) DO NOTHING
            """)
    void insertMapping(ExternalGeneratedImageEntity entity);

    @Select("""
            SELECT
                external_image.id,
                external_image.job_id,
                external_image.owner_id,
                external_image.asset_id,
                external_image.label,
                external_image.image_index,
                external_image.created_at,
                asset.url_path AS url_path,
                asset.original_filename AS original_filename,
                asset.mime_type AS mime_type,
                asset.size_bytes AS size_bytes,
                asset.width,
                asset.height
            FROM artisan_external_generated_images external_image
            INNER JOIN artisan_assets asset
                ON asset.id = external_image.asset_id
            WHERE external_image.owner_id = #{ownerId}
              AND external_image.job_id = #{jobId}
            ORDER BY external_image.image_index ASC, external_image.created_at ASC
            """)
    List<ExternalGeneratedImageEntity> selectByOwnerIdAndJobId(
            @Param("ownerId") UUID ownerId,
            @Param("jobId") UUID jobId
    );
}
