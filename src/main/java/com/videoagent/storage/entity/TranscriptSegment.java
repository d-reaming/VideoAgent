package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 转写分段（含时间戳，问答引用基础）。
 */
@Data
@TableName("transcript_segment")
public class TranscriptSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private Integer seq;

    private Integer startSec;

    private Integer endSec;

    private String text;

    private LocalDateTime createdAt;
}
