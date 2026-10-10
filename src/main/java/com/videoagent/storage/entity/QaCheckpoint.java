package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目标级问答 Checkpoint（WS3）：按 (video_id, goal_digest) 保存问题分析过程的中间产物与最终结果。
 * goal_digest = MD5(归一化问题)；同一视频同一问题的 DONE 结果可幂等复用，避免重复模型调用。
 */
@Data
@TableName("qa_checkpoint")
public class QaCheckpoint {

    /** 阶段：PLANNED / EXECUTED / REVIEWED / DONE */
    public static final String STAGE_PLANNED = "PLANNED";
    public static final String STAGE_EXECUTED = "EXECUTED";
    public static final String STAGE_REVIEWED = "REVIEWED";
    public static final String STAGE_DONE = "DONE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private String goalDigest;

    private String stage;

    private String planJson;

    private String execDraft;

    private String reviewerState;

    private String result;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}