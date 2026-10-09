package com.videoagent.business.agent;

import com.videoagent.access.dto.QaResponse.ReferenceDto;
import com.videoagent.storage.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答引用解析器（亮点 A -> S5 让类型权威化）：从回答文本提取 [mm:ss] 时间戳，
 * 关联到检索命中块，组装结构化引用。修复缺陷 D：识别「（画面）」「【画面@mm:ss】」标记，
 * 使画面引用统一标为 FRAME，后端类型权威，前端只负责渲染不再猜测。
 */
@Slf4j
@Component
public class AnswerReferenceParser {

    /** 普通时间引用，支持可选「（画面）」/「（字幕）」后缀 */
    private static final Pattern TIME_REF = Pattern.compile(
            "\\[(\\d{1,2}):(\\d{2})(?:-(\\d{1,2}):(\\d{2}))?](（画面）|（字幕）)?");
    /** 画面时间引用，如「【画面@01:23】」 */
    private static final Pattern FRAME_REF = Pattern.compile(
            "【画面@(\\d{1,2}):(\\d{2})(?:-(\\d{1,2}):(\\d{2}))?】");
    /** 字幕时间引用，如「【字幕@01:23】」 */
    private static final Pattern OCR_REF = Pattern.compile(
            "【字幕@(\\d{1,2}):(\\d{2})(?:-(\\d{1,2}):(\\d{2}))?】");
    private static final int MATCH_TOLERANCE_SEC = 5;

    public List<ReferenceDto> parse(String answer, Long videoId, List<VectorRepository.SearchHit> hits) {
        List<ReferenceDto> refs = new ArrayList<>();
        Map<String, ReferenceDto> dedup = new LinkedHashMap<>();
        String text = answer == null ? "" : answer;

        Matcher timeMatcher = TIME_REF.matcher(text);
        while (timeMatcher.find()) {
            int startSec = toSec(timeMatcher, 1, 2);
            int endSec = timeMatcher.group(4) != null ? Math.max(startSec, toSec(timeMatcher, 3, 4)) : startSec + 10;
            boolean vision = "（画面）".equals(timeMatcher.group(5));
            boolean ocrText = "（字幕）".equals(timeMatcher.group(5));
            ReferenceDto ref = buildRef(hits, videoId, startSec, endSec, vision, ocrText);
            dedup.put(ref.startSec() + ":" + ref.type(), ref);
        }
        Matcher frameMatcher = FRAME_REF.matcher(text);
        while (frameMatcher.find()) {
            int startSec = toSec(frameMatcher, 1, 2);
            int endSec = frameMatcher.group(4) != null ? Math.max(startSec, toSec(frameMatcher, 3, 4)) : startSec;
            // 画面标记引用：必然为画面
            ReferenceDto ref = buildRef(hits, videoId, startSec, endSec, true, false);
            dedup.put(ref.startSec() + ":" + ref.type(), ref);
        }
        Matcher ocrMatcher = OCR_REF.matcher(text);
        while (ocrMatcher.find()) {
            int startSec = toSec(ocrMatcher, 1, 2);
            int endSec = ocrMatcher.group(4) != null ? Math.max(startSec, toSec(ocrMatcher, 3, 4)) : startSec;
            // 字幕标记引用：必然为字幕
            ReferenceDto ref = buildRef(hits, videoId, startSec, endSec, false, true);
            dedup.put(ref.startSec() + ":" + ref.type(), ref);
        }

        refs.addAll(dedup.values());
        if (refs.isEmpty() && hits != null && !hits.isEmpty()) {
            // 回答未带时间戳时，兜底带出最高分命中，保证溯源可见
            VectorRepository.SearchHit top = hits.get(0);
            refs.add(new ReferenceDto(top.videoId(), top.startSec(), top.endSec(), top.type(),
                    truncate(top.content())));
        }
        return refs;
    }

    private int toSec(Matcher m, int minG, int secG) {
        return Integer.parseInt(m.group(minG)) * 60 + Integer.parseInt(m.group(secG));
    }

    /** 组装单条引用：画面标记强制标 FRAME、字幕标记强制标 OCR（不依赖命中块），否则沿用命中类型，后端类型权威 */
    private ReferenceDto buildRef(List<VectorRepository.SearchHit> hits, Long videoId,
                                  int startSec, int endSec, boolean vision, boolean ocrText) {
        VectorRepository.SearchHit hit = findClosestHit(hits, startSec);
        if (vision || ocrText) {
            // 显式画面/字幕标记：无论是否有命中块，一律按标记类型渲染（AGENT 通道 hits 为空时同样生效）
            return new ReferenceDto(hit != null ? hit.videoId() : videoId,
                    hit != null && hit.startSec() != null ? hit.startSec() : startSec,
                    hit != null && hit.endSec() != null ? hit.endSec() : endSec,
                    vision ? "FRAME" : "OCR",
                    hit != null ? truncate(hit.content()) : null);
        }
        if (hit != null && "FRAME".equals(hit.type())) {
            return new ReferenceDto(hit.videoId(),
                    hit.startSec() != null ? hit.startSec() : startSec,
                    hit.endSec() != null ? hit.endSec() : endSec,
                    "FRAME",
                    truncate(hit.content()));
        }
        return new ReferenceDto(hit != null ? hit.videoId() : videoId,
                hit != null && hit.startSec() != null ? hit.startSec() : startSec,
                hit != null && hit.endSec() != null ? hit.endSec() : endSec,
                hit != null ? hit.type() : "TEXT",
                hit != null ? truncate(hit.content()) : null);
    }

    private VectorRepository.SearchHit findClosestHit(List<VectorRepository.SearchHit> hits, int sec) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        VectorRepository.SearchHit best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (VectorRepository.SearchHit hit : hits) {
            int start = hit.startSec() == null ? Integer.MAX_VALUE : hit.startSec();
            int diff = Math.abs(start - sec);
            if (diff < bestDiff && diff <= MATCH_TOLERANCE_SEC) {
                best = hit;
                bestDiff = diff;
            }
        }
        return best;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}