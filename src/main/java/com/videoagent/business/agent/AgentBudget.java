package com.videoagent.business.agent;

/**
 * Agent 预算硬上限（单次问答粒度）：LLM 调用次数 + 累计 token + 墙钟超时。
 * 防止工具循环/Reviewer 回炉无界消费模型配额。
 * 配置项 video-agent.agent.budget.*（在 AgentRuntime 中注入后按次问答创建新实例）。
 */
public class AgentBudget {

    private final int maxLlmCalls;
    private final int maxTokens;
    private final long deadlineMillis;
    private final long startedAtNanos = System.nanoTime();

    private int llmCalls;
    private int tokens;

    public AgentBudget(int maxLlmCalls, int maxTokens, long deadlineMillis) {
        this.maxLlmCalls = maxLlmCalls;
        this.maxTokens = maxTokens;
        this.deadlineMillis = deadlineMillis;
    }

    /** 每次调用 LLM 前消费一次调用额度 */
    public void consumeLlmCall() {
        llmCalls++;
    }

    /** 从 LLM 响应累计 token（input+output）；null/负值按 0 处理 */
    public void consumeTokens(int n) {
        if (n > 0) {
            tokens += n;
        }
    }

    /** 是否已超任一预算上限（轮次 / token / 墙钟） */
    public boolean isExceeded() {
        return llmCalls >= maxLlmCalls || tokens >= maxTokens || elapsedMs() > deadlineMillis;
    }

    /** 已运行墙钟毫秒数 */
    public long elapsedMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    public int llmCalls() {
        return llmCalls;
    }

    public int tokens() {
        return tokens;
    }

    public int maxLlmCalls() {
        return maxLlmCalls;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public long deadlineMillis() {
        return deadlineMillis;
    }
}