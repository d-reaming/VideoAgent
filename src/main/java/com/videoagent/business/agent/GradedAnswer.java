package com.videoagent.business.agent;

import com.videoagent.business.evidence.Assertion;

import java.util.List;

/**
 * 分级回答（AnswerAgent 的结构化输出，S2）：
 * answer 为给用户的最终可读回答（含引用标注）；assertions 为可独立核验的断言列表，
 * 供 Reviewer(S3) 逐条做保真监督（置信越权 / 漏答 / 幻觉）。
 */
public record GradedAnswer(String answer, List<Assertion> assertions) {

    /** 兜底：未产出结构化断言时退化为纯文本回答 */
    public static GradedAnswer plain(String answer) {
        return new GradedAnswer(answer, List.of());
    }
}