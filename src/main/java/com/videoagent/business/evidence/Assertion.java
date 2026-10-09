package com.videoagent.business.evidence;

import java.util.List;

/**
 * 断言（AnswerAgent 分级输出的最小单元，S2 使用）。
 * 每一条 = 一个可核验的陈述 + 置信分级 + 支撑证据列表。
 * Reviewer 将逐条核验：置信是否越权、是否漏答、是否有幻觉。
 * S1 仅定义 schema 骨架，不改动 AnswerAgent 调用。
 */
public record Assertion(
        String text,
        Confidence confidence,
        List<Evidence> evidence
) {
}