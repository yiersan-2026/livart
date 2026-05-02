package com.artisanlab.external;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

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

    @Select("""
            SELECT id, user_id, source_url, source_host, image_count, created_at, updated_at, last_parsed_at
            FROM artisan_external_image_parse_history
            WHERE user_id = #{userId}
            ORDER BY last_parsed_at DESC
            LIMIT #{limit}
            """)
    List<ExternalImageParseHistoryEntity> selectRecentByUserId(UUID userId, int limit);
}
