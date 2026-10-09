package com.videoagent.middleware.event;

/**
 * 流水线事件体（Kafka JSON 反序列化目标类型）。
 */
public record VideoEvent(Long videoId) {
}
