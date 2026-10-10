package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 失败/死信台账：视频处理阶段每次失败落一行，支撑自动重试退避与死信审计。
 * 重试耗尽（attempt 达 AUTO_RETRY_MAX）后 task_record 置 DEAD，本表记录 dead_at 作为死信账本。
 */
@Data
@TableName("task_failure")
public class TaskFailure {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    /** 失败阶段，见 {@link com.videoagent.storage.entity.enums.TaskStage}（如 TRANSCRIBING/FRAMING/EMBEDDING） */
    private String stage;

    private String errorMsg;

    /** 1=可重试（阶段内多为瞬时基础设施问题），0=永久失败 */
    private Integer retryable;

    /** 第几次失败尝试（1 起） */
    private Integer attempt;

    /** 成为死信的触发时刻；NULL 表示仍可自动重试 */
    private LocalDateTime deadAt;

    private LocalDateTime createdAt;
}