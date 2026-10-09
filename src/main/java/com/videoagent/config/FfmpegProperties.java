package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ffmpeg 可执行文件配置。
 */
@ConfigurationProperties(prefix = "video-agent.ffmpeg")
public record FfmpegProperties(String path) {
}
