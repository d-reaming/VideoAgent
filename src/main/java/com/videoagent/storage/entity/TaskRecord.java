package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处理任务记录：每视频一条，阶段流转更新同一行，记录耗时/错误/重试，支撑可观测性。
 */
@Data
@TableName("task_record")
public class TaskRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    /** 当前阶段，见 {@link com.videoagent.storage.entity.enums.TaskStage} */
    private String stage;

    /** 任务终态常量：RUNNING/RUNNING→FAILED/DONE/DEAD */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    /** 死信终态：自动重试耗尽后置此，不再自动重试；视频保持 FAILED */
    public static final String STATUS_DEAD = "DEAD";

    /** RUNNING / DONE / FAILED / DEAD */
    private String status;

    private Integer retryCount;

    private String errorMsg;

    /** JSON：{"transcribe_ms":..,"frame_ms":..,"embed_ms":..} */
    private String phaseDurations;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
