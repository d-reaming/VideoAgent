package com.videoagent.business.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.VideoOcr;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoOcrMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 引用证据确定性校验（无 LLM）。对回答里提取出的每条引用，核对其时间戳是否落在真实
 * 转写/画面/OCR 区间、引用文本能否在对应原文中找到，防模型编造时间戳或引用串到错误片段。
 *
 * <p>真源直接查询三个原始表（transcript_segment / frame_record / video_ocr），与
 * {@code GlobalContextTool.build} 同源但更轻，避免跨层依赖。返回 verified + 中文 reason，
 * 由上层（QaFacade）映射回 {@code ReferenceDto} 的 verified/verifyNote 字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceVerifier {

    private final TranscriptSegmentMapper segmentMapper;
    private final VideoOcrMapper ocrMapper;
    private final FrameRecordMapper frameMapper;

    /** 时间交叠容差（秒），与 AnswerReferenceParser 的 MATCH_TOLERANCE_SEC 语义一致 */
    private static final int TOLERANCE_SEC = 5;
    /** 二元组 Dice 相似度阈值：引用文本与真源原文的公共二字符组占比 */
    private static final double BIGRAM_THRESHOLD = 0.4;

    /** 引用类型 → 期望真源类型；FRAME→画面描述、OCR→字幕、其余→语音，容忍互相回退 */
    private static final Set<String> AUDIO_LIKE = Set.of("TEXT", "AUDIO");

    /**
     * 单条引用校验结果。
     *
     * @param type      引用类型（FRAME/OCR/TEXT）
     * @param startSec  引用起点（秒）
     * @param endSec    引用终点（秒）
     * @param quote     引用文本（可能为空，仅做时间校验）
     */
    public record Verification(String type, Integer startSec, Integer endSec, String quote,
                               boolean verified, String reason) {
    }

    /** 批量校验：每个 videoId 只查一次原始表 */
    public List<Verification> verifyAll(Long videoId, List<VerificationSource> sources) {
        if (videoId == null || sources == null || sources.isEmpty()) {
            return List.of();
        }
        Corpus corpus = Corpus.load(videoId, segmentMapper, ocrMapper, frameMapper);
        List<Verification> out = new ArrayList<>(sources.size());
        for (VerificationSource src : sources) {
            out.add(verifyOne(corpus, src.type(), src.startSec(), src.endSec(), src.quote()));
        }
        return out;
    }

    /** 供上层逐条调用的便捷入口 */
    public Verification verifyOne(Long videoId, VerificationSource src) {
        return verifyOne(Corpus.load(videoId, segmentMapper, ocrMapper, frameMapper),
                src.type(), src.startSec(), src.endSec(), src.quote());
    }

    private Verification verifyOne(Corpus corpus, String type, Integer startSec, Integer endSec, String quote) {
        if (startSec == null) {
            return new Verification(type, startSec, endSec, quote, false, "引用缺少时间戳，无法校验");
        }
        List<Corpus.Source> timeCandidates = corpus.covering(type, startSec, endSec);
        if (timeCandidates.isEmpty()) {
            return new Verification(type, startSec, endSec, quote, false,
                    String.format("时间 [%s-%s] 无匹配的语音/画面/字幕内容", fmt(startSec), fmt(endSec)));
        }
        if (quote == null || normalize(quote).isEmpty()) {
            return new Verification(type, startSec, endSec, quote, true, "时间戳落在真实内容区间");
        }
        // 时间命中的候选中，要求能找到引用文本（归一化后包含关系或二元组相似）
        String nQuote = normalize(quote);
        for (Corpus.Source src : timeCandidates) {
            if (textMatches(nQuote, src.text())) {
                return new Verification(type, startSec, endSec, quote, true, "时间戳与原文均可核对");
            }
        }
        return new Verification(type, startSec, endSec, quote, false,
                String.format("时间 [%s-%s] 有内容，但引文「%s」在此处原文中未找到",
                        fmt(startSec), fmt(endSec), truncate(quote)));
    }

    private boolean textMatches(String nQuote, String sourceText) {
        if (sourceText == null) {
            return false;
        }
        String nSource = normalize(sourceText);
        if (nSource.isEmpty()) {
            return false;
        }
        if (nSource.contains(nQuote)) {
            return true;
        }
        // 长谓词：用二元组 Dice 相似度兜底（对专名/纠错场景更宽容）
        if (nQuote.length() >= 4) {
            return bigramDice(nQuote, nSource) >= BIGRAM_THRESHOLD;
        }
        // 短引文（≤2 字）只认可包含关系，避免过拟合
        return false;
    }

    private static double bigramDice(String a, String b) {
        Set<String> ga = biGrams(a);
        Set<String> gb = biGrams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0d;
        }
        int inter = 0;
        for (String g : ga) {
            if (gb.contains(g)) {
                inter++;
            }
        }
        return 2.0 * inter / (ga.size() + gb.size());
    }

    private static Set<String> biGrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (s.length() < 2) {
            return set;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    /** 归一化：去空白标点、转小写（汉字可保留，仅作统一大小写便于英文专名匹配） */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String fmt(Integer sec) {
        if (sec == null) {
            return "00:00";
        }
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /** 校验输入（由上层把 ReferenceDto 映射而来，避免本层依赖 access.dto） */
    public record VerificationSource(String type, Integer startSec, Integer endSec, String quote) {
    }

    /** 一视频校验语料：只查一次原始表，线程内复用 */
    private static final class Corpus {
        final List<Source> sources = new ArrayList<>();

        /** 查原始三表构建：语音、画面描述、字幕 */
        static Corpus load(Long videoId, TranscriptSegmentMapper sm, VideoOcrMapper om,
                           FrameRecordMapper fm) {
            Corpus c = new Corpus();
            if (videoId == null) {
                return c;
            }
            sm.selectList(new LambdaQueryWrapper<TranscriptSegment>()
                            .eq(TranscriptSegment::getVideoId, videoId)
                            .orderByAsc(TranscriptSegment::getSeq))
                    .forEach(s -> c.sources.add(new Source(AUDIO_LIKE, s.getStartSec(), s.getEndSec(), s.getText())));
            om.selectList(new LambdaQueryWrapper<VideoOcr>()
                            .eq(VideoOcr::getVideoId, videoId)
                            .orderByAsc(VideoOcr::getFrameTime))
                    .forEach(r -> c.sources.add(new Source(Set.of("OCR"), r.getFrameTime(), r.getFrameTime(), r.getText())));
            fm.selectList(new LambdaQueryWrapper<FrameRecord>()
                            .eq(FrameRecord::getVideoId, videoId)
                            .orderByAsc(FrameRecord::getFrameTime))
                    .forEach(f -> {
                        if (f.getDescription() != null && !f.getDescription().isBlank()) {
                            c.sources.add(new Source(Set.of("VISION"), f.getFrameTime(), f.getFrameTime(),
                                    f.getDescription()));
                        }
                    });
            return c;
        }

        /** 找出与 [start,end] 时间交叠（±容差）且类型匹配（含回退）的所有候选 */
        List<Source> covering(String refType, Integer startSec, Integer endSec) {
            Set<String> wanted = typeFamily(refType);
            int s = startSec == null ? 0 : startSec;
            int e = (endSec == null || endSec < s) ? s : endSec;
            List<Source> matched = new ArrayList<>();
            for (Source src : sources) {
                if (!src.types.stream().anyMatch(wanted::contains)) {
                    continue;
                }
                boolean overlap = (e + TOLERANCE_SEC) >= src.start && (s - TOLERANCE_SEC) <= src.end;
                if (overlap) {
                    matched.add(src);
                }
            }
            return matched;
        }

        private static Set<String> typeFamily(String refType) {
            if ("FRAME".equals(refType) || "VISION".equals(refType)) {
                return Set.of("VISION");
            }
            if ("OCR".equals(refType)) {
                return Set.of("OCR");
            }
            // TEXT/AUDIO：语音为主，容忍回退到画面/字幕（回答常未标注类型）
            return Set.of("AUDIO", "VISION", "OCR", "TEXT");
        }

        record Source(Set<String> types, int start, int end, String text) {
        }
    }
}