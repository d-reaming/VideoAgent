package com.videoagent.business.harness;

/**
 * 问题模式（答案生成索引）：由 RuleRouter.matchMode 依据问题关键词确定性判定，
 * 决定回答策略与检索方式。SUMMARY 走 map-reduce 全量归纳；SPOT/VISUAL 走向量检索；
 * ANALYSIS 走多 Agent 语义编排；GENERAL 为无单视频语境的全局摘要回退。
 */
public enum QuestionMode {
    SUMMARY, SPOT, VISUAL, ANALYSIS, GENERAL
}