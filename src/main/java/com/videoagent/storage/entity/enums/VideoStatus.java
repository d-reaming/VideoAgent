package com.videoagent.storage.entity.enums;

/**
 * 视频处理状态机：UPLOADED → TRANSCRIBING → FRAMING → EMBEDDING → READY | FAILED
 */
public enum VideoStatus {
    UPLOADED,
    TRANSCRIBING,
    FRAMING,
    EMBEDDING,
    READY,
    FAILED
}
