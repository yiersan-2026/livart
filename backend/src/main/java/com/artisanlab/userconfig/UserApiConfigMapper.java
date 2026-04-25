package com.artisanlab.userconfig;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface UserApiConfigMapper extends BaseMapper<UserApiConfigEntity> {
    @Select("""
            SELECT user_id, base_url, api_key, image_model, chat_model, created_at, updated_at
            FROM artisan_user_api_configs
            WHERE user_id = #{userId}
            """)
    UserApiConfigEntity findByUserId(@Param("userId") UUID userId);

    @Insert("""
            INSERT INTO artisan_user_api_configs (
                user_id, base_url, api_key, image_model, chat_model, created_at, updated_at
            )
            VALUES (
                #{userId}, #{baseUrl}, #{apiKey}, #{imageModel}, #{chatModel}, NOW(), NOW()
            )
            ON CONFLICT (user_id) DO UPDATE SET
                base_url = EXCLUDED.base_url,
                api_key = EXCLUDED.api_key,
                image_model = EXCLUDED.image_model,
                chat_model = EXCLUDED.chat_model,
                updated_at = NOW()
            """)
    void upsert(UserApiConfigEntity entity);
}
