package com.videoagent.business.evidence;

import java.util.Map;

/**
 * 证据条目（证据湖统一 schema 的原子单位）。
 * 一份参照来源（语音/画面/检索/元数据）+ 时间范围 + 文本 + 置信。
 * 全局视图（GlobalContextTool）与检索视图（RagSearchTool）都产出统一 Evidence，保证一体性。
 */
public record Evidence(
        EvidenceType type,
        Integer startSec,
        Integer endSec,
        String text,
        Confidence confidence
) {

    /** 渲染为上下文文本，供 LLM 直接阅读（格式与现有引用标注一致） */
    public String toText() {
        String label = switch (type) {
            case AUDIO -> "语音";
            case VISION -> "画面";
            case RETRIEVED -> "检索";
            case META -> "元数据";
        };
        if (type == EvidenceType.META) {
            return label + "：" + text;
        }
        return "[" + fmt(startSec) + "-" + fmt(endSec) + "] " + label + "：" + text;
    }

    /** 渲染为可序列化视图（前端/落库引用用） */
    public Map<String, Object> toMap() {
        return Map.of(
                "type", type.name(),
                "startSec", startSec == null ? 0 : startSec,
                "endSec", endSec == null ? 0 : endSec,
                "text", text,
                "confidence", confidence == null ? null : confidence.name());
    }

    private static String fmt(Integer sec) {
        if (sec == null) {
            return "00:00";
        }
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }
}