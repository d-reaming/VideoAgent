package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关键帧记录（帧时间 + 图片路径 + 视觉描述）。
 */
@Data
@TableName("frame_record")
public class FrameRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    /** 帧在视频中的时间（秒） */
    private Integer frameTime;

    private String imagePath;

    private String description;

    private LocalDateTime createdAt;
}
