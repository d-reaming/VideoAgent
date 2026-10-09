package com.videoagent.storage.entity.enums;

/**
 * 视频物理清理状态（软删后由后台清理，逐步从 PENDING → DONE 并物理删除 video 行）。
 */
public enum CleanupStatus {
    /** 未删除 */
    NONE,
    /** 已软删，等待/正在物理清理 */
    PENDING,
    /** 物理清理完成 */
    DONE
}