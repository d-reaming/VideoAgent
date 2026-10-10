package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储目录配置。
 */
@ConfigurationProperties(prefix = "video-agent.storage")
public record StorageProperties(String root) {

    /** 视频原文件目录 */
    public String videosDir() {
        return root + "/videos";
    }

    /** 音频目录 */
    public String audioDir() {
        return root + "/audio";
    }

    /** 关键帧图片目录 */
    public String framesDir() {
        return root + "/frames";
    }

    /** 分片上传临时目录（WS4）：按 uploadId 存放 .part 分片 */
    public String chunkDir() {
        return root + "/chunk-uploads";
    }
}
