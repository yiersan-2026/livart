package com.artisanlab.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Insert("""
            INSERT INTO artisan_users (id, username, display_name, password_hash, created_at, updated_at)
            VALUES (#{id}, #{username}, #{displayName}, #{passwordHash}, NOW(), NOW())
            """)
    void insertUser(UserEntity entity);

    @Select("""
            SELECT id, username, display_name, password_hash, created_at, updated_at
            FROM artisan_users
            WHERE username = #{username}
            """)
    UserEntity findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, display_name, password_hash, created_at, updated_at
            FROM artisan_users
            WHERE id = #{id}
            """)
    UserEntity findById(@Param("id") UUID id);
}
