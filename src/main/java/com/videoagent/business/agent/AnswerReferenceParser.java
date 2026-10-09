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
 * 回答引用解析器（亮点 A）：从回答文本提取 [mm:ss] 时间戳，关联到检索命中块，组装结构化引用。
 */
@Slf4j
@Component
public class AnswerReferenceParser {

    private static final Pattern TIME_REF = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:-(\\d{1,2}):(\\d{2}))?]");
    private static final int MATCH_TOLERANCE_SEC = 5;

    public List<ReferenceDto> parse(String answer, Long videoId, List<VectorRepository.SearchHit> hits) {
        List<ReferenceDto> refs = new ArrayList<>();
        Map<String, ReferenceDto> dedup = new LinkedHashMap<>();
        Matcher matcher = TIME_REF.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int startSec = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
            int endSec = matcher.group(4) != null
                    ? Integer.parseInt(matcher.group(3)) * 60 + Integer.parseInt(matcher.group(4))
                    : startSec + 10;
            VectorRepository.SearchHit hit = findClosestHit(hits, startSec);
            ReferenceDto ref = new ReferenceDto(
                    hit != null ? hit.videoId() : videoId,
                    hit != null && hit.startSec() != null ? hit.startSec() : startSec,
                    hit != null && hit.endSec() != null ? hit.endSec() : endSec,
                    hit != null ? hit.type() : "TEXT",
                    hit != null ? truncate(hit.content()) : null);
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
