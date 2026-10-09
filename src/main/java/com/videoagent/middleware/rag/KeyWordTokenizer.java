package com.videoagent.middleware.rag;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量关键词分词器（自建大字符倒排表用，零外部依赖）：
 * - 英文/数字/下划线：按 \w+ 切分并转小写（保留 ≥2 字符或纯数字 token，兼顾代码标识符/端口/参数）
 * - 中文：对连续汉字取「双字符 bigram」（如 "视频问答" → 视频/频问/问答）做近似分词，兼顾子串精确召回与索引规模
 * - 入库前剥离时间戳/画面锚点（[00:00-01:00]、【画面@01:23】）等纯数字噪声
 */
@Component
public class KeyWordTokenizer {

    private static final Pattern LATIN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]+");
    /** 时间戳锚点：TEXT 的 [00:00-01:00] 与 FRAME 的 【画面@01:23】 */
    private static final Pattern TIMECODE = Pattern.compile(
            "\\[\\d{1,3}:\\d{2}(?:[\\-–:]\\d{1,3}:\\d{2})?\\]");
    private static final Pattern FRAME_ANCHOR = Pattern.compile("【[^】]*?@\\d{1,3}:\\d{2}】");

    /**
     * 切分出 chunk 内容的关键词 → 频次（出现几次）。
     */
    public Map<String, Integer> tokenize(String content) {
        Map<String, Integer> map = new HashMap<>();
        if (content == null || content.isBlank()) {
            return map;
        }
        String cleaned = FRAME_ANCHOR.matcher(TIMECODE.matcher(content).replaceAll(" ")).replaceAll(" ");

        Matcher latin = LATIN.matcher(cleaned);
        while (latin.find()) {
            String tok = latin.group().toLowerCase(Locale.ROOT);
            // 保留 ≥2 字符的字母/下划线 token，以及任意长度纯数字 token（端口/参数有检索价值）
            if (tok.length() >= 2 || isAllDigits(tok)) {
                map.merge(tok, 1, Integer::sum);
            }
        }

        Matcher cjk = CJK.matcher(cleaned);
        while (cjk.find()) {
            String han = cjk.group();
            for (int i = 0; i + 2 <= han.length(); i++) {
                map.merge(han.substring(i, i + 2), 1, Integer::sum);
            }
        }
        return map;
    }

    private boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}