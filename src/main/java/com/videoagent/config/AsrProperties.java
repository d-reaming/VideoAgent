package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ASR 双通道配置：本地 faster-whisper 为主，云端兜底。
 */
@ConfigurationProperties(prefix = "video-agent.asr")
public record AsrProperties(Local local, Cloud cloud) {

    public record Local(Boolean enabled, String python, String workerScript, String workerServerScript,
                        String modelSize, String device, String language) {
    }

    public record Cloud(Boolean enabled) {
    }
}
