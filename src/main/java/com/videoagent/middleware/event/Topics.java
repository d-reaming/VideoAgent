package com.videoagent.middleware.event;

/**
 * Kafka 事件主题常量：视频处理流水线事件。
 */
public final class Topics {

    private Topics() {
    }

    /** 上传完成 → 触发转写 */
    public static final String VIDEO_UPLOADED = "video.uploaded";

    /** 转写完成 → 触发抽帧 */
    public static final String VIDEO_TRANSCRIBED = "video.transcribed";

    /** 抽帧完成 → 触发切块/Embedding */
    public static final String VIDEO_FRAMED = "video.framed";

    /** 全部处理完成 → 通知就绪 */
    public static final String VIDEO_READY = "video.ready";

    public static final String[] ALL = {VIDEO_UPLOADED, VIDEO_TRANSCRIBED, VIDEO_FRAMED, VIDEO_READY};
}
