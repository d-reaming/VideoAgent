package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.business.evidence.Assertion;
import com.videoagent.business.evidence.Confidence;
import com.videoagent.business.evidence.Evidence;
import com.videoagent.business.evidence.EvidenceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分级回答解析器：从 AnswerAgent 的结构化输出中提取 answer + assertions。
 * 模型未按 JSON/CDI 或 JSON 不完整时返回 null，由调用方兜底为纯文本（保证链路健壮）。
 */
@Slf4j
@Component
public class GradedAnswerParser {

    private final ObjectMapper objectMapper;

    public GradedAnswerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GradedAnswer parse(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            String answer = root.path("answer").asText();
            if (answer == null || answer.isBlank()) {
                return null;
            }
            List<Assertion> assertions = new ArrayList<>();
            JsonNode arr = root.path("assertions");
            if (arr.isArray()) {
                for (JsonNode a : arr) {
                    String assertionText = a.path("text").asText("");
                    if (assertionText.isBlank()) {
                        continue;
                    }
                    Confidence conf = parseConfidence(a.path("confidence").asText(""));
                    List<Evidence> evidence = new ArrayList<>();
                    JsonNode evArr = a.path("evidence");
                    if (evArr.isArray()) {
                        for (JsonNode v : evArr) {
                            evidence.add(new Evidence(
                                    parseType(v.path("type").asText("")),
                                    v.hasNonNull("startSec") ? v.path("startSec").asInt() : null,
                                    v.hasNonNull("endSec") ? v.path("endSec").asInt() : null,
                                    v.path("text").asText(""),
                                    conf));
                        }
                    }
                    assertions.add(new Assertion(assertionText, conf, evidence));
                }
            }
            return new GradedAnswer(answer, assertions);
        } catch (Exception e) {
            log.warn("[graded] 解析结构化回答失败，回退纯文本: {}", e.getMessage());
            return null;
        }
    }

    private Confidence parseConfidence(String s) {
        try {
            return Confidence.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return Confidence.DIRECT;
        }
    }

    private EvidenceType parseType(String s) {
        try {
            return EvidenceType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return EvidenceType.RETRIEVED;
        }
    }
}