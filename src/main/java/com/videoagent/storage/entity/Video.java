package com.videoagent.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频元数据。
 */
@Data
@TableName("video")
public class Video {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String filename;

    private String filePath;

    private Integer durationSec;

    /** 状态值见 {@link com.videoagent.storage.entity.enums.VideoStatus} */
    private String status;

    /** 属主用户 id（多租户过滤），0 表示无属主（存量数据） */
    private Long userId;

    /** 软删时间：非空表示逻辑删除（前台不可见），供后台清理 */
    private LocalDateTime deletedAt;

    /** 清理状态：NONE / PENDING / DONE，见 {@link com.videoagent.storage.entity.enums.CleanupStatus} */
    private String cleanupStatus;

    /** 已成功向量化入库的块数（Embedding checkpoint，断点续传用） */
    private Long indexedSeq;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
