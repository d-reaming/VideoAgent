package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ASR 双通道配置：本地 faster-whisper 为主，云端兜底。
 * <ul>
 *   <li>language-mode: AUTO=自动识别源语言(支持混语言)，FIXED=固定用 language 指定目标语言</li>
 *   <li>model-size / device: 本地转写精度与设备(可选项，提升型)</li>
 *   <li>normalize: 实体归一化(去噪型修正，必做，可用开关简化)</li>
 *   <li>correct: 音视校正(去噪型修正，必做)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "video-agent.asr")
public record AsrProperties(Local local, Cloud cloud, Normalize normalize, Correct correct) {

    public record Local(Boolean enabled, String python, String workerScript, String workerServerScript,
                        String modelSize, String device, String language, String languageMode) {
    }

    public record Cloud(Boolean enabled) {
    }

    /** 实体归一化：ASR 后轻量 LLM 只修正已知实体的明显乱码（不扩写、控幻觉） */
    public record Normalize(Boolean enabled) {
    }

    /** 音视校正：用画面帧字幕校正同时间戳 ASR 乱码专名 */
    public record Correct(Boolean enabled) {
    }
}
