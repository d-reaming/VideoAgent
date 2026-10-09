package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多轮问答会话。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 可为空：全局问答 */
    private Long videoId;

    /** 属主用户 id（会话归属校验/过滤） */
    private Long userId;

    private String title;

    private LocalDateTime createdAt;
}
