package com.artisanlab.stats;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteStatsMapper {
    @Select("SELECT COUNT(*) FROM artisan_users")
    long countUsers();

    @Select("""
            WITH message_cards AS (
                SELECT NULLIF(card.value ->> 'imageId', '') AS image_key
                FROM artisan_canvases canvas
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN jsonb_typeof(canvas.state_json -> 'messages') = 'array'
                        THEN canvas.state_json -> 'messages'
                        ELSE '[]'::jsonb
                    END
                ) AS message(value)
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN jsonb_typeof(message.value -> 'imageResultCards') = 'array'
                        THEN message.value -> 'imageResultCards'
                        ELSE '[]'::jsonb
                    END
                ) AS card(value)
            ),
            generated_items AS (
                SELECT COALESCE(
                    NULLIF(item.value ->> 'id', ''),
                    NULLIF(item.value ->> 'assetId', ''),
                    NULLIF(item.value ->> 'content', ''),
                    NULLIF(item.value ->> 'previewContent', ''),
                    NULLIF(item.value ->> 'thumbnailContent', '')
                ) AS image_key
                FROM artisan_canvases canvas
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN jsonb_typeof(canvas.state_json -> 'items') = 'array'
                        THEN canvas.state_json -> 'items'
                        ELSE '[]'::jsonb
                    END
                ) AS item(value)
                WHERE item.value ->> 'type' = 'image'
                  AND item.value ->> 'status' = 'completed'
                  AND COALESCE(item.value ->> 'source', '') NOT IN ('upload', 'external', 'social', 'social-media', 'crop')
                  AND (
                      COALESCE(item.value ->> 'content', '') <> ''
                      OR COALESCE(item.value ->> 'assetId', '') <> ''
                      OR COALESCE(item.value ->> 'previewContent', '') <> ''
                      OR COALESCE(item.value ->> 'thumbnailContent', '') <> ''
                  )
                  AND (
                      COALESCE(item.value ->> 'originalPrompt', '') <> ''
                      OR COALESCE(item.value ->> 'optimizedPrompt', '') <> ''
                      OR COALESCE(item.value ->> 'prompt', '') <> ''
                  )
                  AND COALESCE(item.value ->> 'originalPrompt', '') NOT LIKE '从 % 裁剪生成'
                  AND COALESCE(item.value ->> 'optimizedPrompt', '') NOT LIKE '从 % 裁剪生成'
                  AND COALESCE(item.value ->> 'prompt', '') NOT LIKE '从 % 裁剪生成'
            ),
            generated_images AS (
                SELECT image_key FROM message_cards WHERE image_key IS NOT NULL
                UNION
                SELECT image_key FROM generated_items WHERE image_key IS NOT NULL
            )
            SELECT COUNT(*) FROM generated_images
            """)
    long countGeneratedImages();
}
