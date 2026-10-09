package com.videoagent.business.agent;

import java.util.List;

/**
 * 规划结果（PlannerAgent 结构化输出）：
 * 由语义编排 Agent 拆解用户问题后产出，真正驱动 Executor 的检索参数与模式。
 */
public record Plan(
        String intent,           // SUMMARY | FACTUAL | VISUAL | TEMPORAL | COMPLEX
        String retrieveMode,     // FULL_TEXT | TOP_K | TIMELINE
        int textTopK,
        int frameTopK,
        List<String> keywords,   // 建议检索关键词
        boolean needFullText     // 是否需要拉取全量转写
) {
    public static Plan fallback() {
        return new Plan("GENERAL", "TOP_K", 8, 4, List.of(), false);
    }

    /** 供执行 Agent 展示的可读规划要点 */
    public String describe() {
        return "intent=" + intent
                + ", retrieveMode=" + retrieveMode
                + ", textTopK=" + textTopK
                + ", frameTopK=" + frameTopK
                + ", needFullText=" + needFullText
                + ", keywords=" + (keywords.isEmpty() ? "（空）" : String.join("、", keywords));
    }
}