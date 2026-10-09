package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR 第三信源配置：本地 RapidOCR 持久化 worker（字幕/屏显文字提取）。
 * <ul>
 *   <li>enabled: OCR 通道开关；关闭时流水线照常、各消费点判空降级</li>
 *   <li>min-confidence: 识别置信度阈值，低于该值的文本丢弃</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "video-agent.ocr")
public record OcrProperties(Local local) {

    public record Local(Boolean enabled, String python, String workerServerScript,
                        String device, Double minConfidence) {
    }
}
