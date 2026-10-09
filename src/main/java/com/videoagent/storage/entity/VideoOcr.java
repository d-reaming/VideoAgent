package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OCR 文字记录（第三信源：字幕/屏显文字，按关键帧时间锚定）。
 */
@Data
@TableName("video_ocr")
public class VideoOcr {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    /** 帧在视频中的时间（秒） */
    private Integer frameTime;

    private String text;

    /** OCR 识别置信度（0~1） */
    private BigDecimal confidence;

    private LocalDateTime createdAt;
}
