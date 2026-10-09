package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录用户（认证 + 限流维度）。
 */
@Data
@TableName("app_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希后的密码，不存明文 */
    private String passwordHash;

    private LocalDateTime createdAt;
}