package com.artisanlab.external;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExternalImageParseHistoryMapper extends BaseMapper<ExternalImageParseHistoryEntity> {
    @Insert("""
            INSERT INTO artisan_external_image_parse_history (
                id, user_id, source_url, source_host, image_count, created_at, updated_at, last_parsed_at
            )
            VALUES (
                #{id}, #{userId}, #{sourceUrl}, #{sourceHost}, #{imageCount}, NOW(), NOW(), NOW()
            )
            ON CONFLICT (user_id, source_url) DO UPDATE SET
                source_host = EXCLUDED.source_host,
                image_count = EXCLUDED.image_count,
                updated_at = NOW(),
                last_parsed_at = NOW()
            """)
    void upsert(ExternalImageParseHistoryEntity entity);
}
