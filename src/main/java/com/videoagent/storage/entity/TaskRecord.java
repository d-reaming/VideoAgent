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

    /** RUNNING / DONE / FAILED */
    private String status;

    private Integer retryCount;

    private String errorMsg;

    /** JSON：{"transcribe_ms":..,"frame_ms":..,"embed_ms":..} */
    private String phaseDurations;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
