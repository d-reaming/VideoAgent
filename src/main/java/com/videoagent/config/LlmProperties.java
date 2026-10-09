package com.videoagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 供应商配置：DeepSeek（主问答）+ 通义 DashScope（视觉/Embedding）。
 */
@ConfigurationProperties(prefix = "video-agent.llm")
public record LlmProperties(DeepSeek deepseek, DashScope dashscope) {

    public record DeepSeek(String baseUrl, String apiKey, String model, Double temperature) {
    }

    public record DashScope(String baseUrl, String apiKey, String visionModel, String embeddingModel,
                            Integer embeddingDim) {
    }
}
