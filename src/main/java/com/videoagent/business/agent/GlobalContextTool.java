package com.videoagent.business.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.business.evidence.Confidence;
import com.videoagent.business.evidence.Evidence;
import com.videoagent.business.evidence.EvidenceType;
import com.videoagent.business.video.CorrectionService;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.VideoOcr;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoMapper;
import com.videoagent.storage.mapper.VideoOcrMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局上下文工具（证据湖双视图之【全局视图】）：
 * 汇总某视频的全量语音转写(AUDIO) + 全帧画面描述(VISION) + 画面文字(OCR) + 元数据(META) 为统一 Evidence。
 * 供总结/大意/内容概述等全局型问题一次性获得完整依据，而非依赖 topK 片段截断语境。
 * 与检索视图 RagSearchTool(top-K) 产出同一 {@link Evidence} 结构，保证一体性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalContextTool {

    private final VideoMapper videoMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FrameRecordMapper frameMapper;
    private final VideoOcrMapper videoOcrMapper;
    private final CorrectionService correctionService;

    /** 全局可读上下文（供 LLM 直接阅读），返回空引用时以文本提示兜底 */
    public String globalContext(Long videoId) {
        List<Evidence> evidence = build(videoId);
        List<Evidence> nonMeta = evidence.stream()
                .filter(e -> e.type() != EvidenceType.META)
                .toList();
        if (nonMeta.isEmpty()) {
            return "该视频暂无可用语音或画面内容。";
        }
        StringBuilder sb = new StringBuilder();
        evidence.forEach(e -> sb.append(e.toText()).append('\n'));
        return sb.toString().strip();
    }

    /** 汇总为统一 Evidence 列表（全局视图）：元数据 → 语音 → 画面，各按时间升序 */
    public List<Evidence> build(Long videoId) {
        List<Evidence> list = new ArrayList<>();

        Video video = videoMapper.selectById(videoId);
        if (video != null) {
            list.add(new Evidence(EvidenceType.META, null, null,
                    "标题《" + video.getTitle() + "》，时长约 "
                            + (video.getDurationSec() == null ? "未知" : video.getDurationSec() + " 秒") + "，状态 "
                            + video.getStatus(),
                    Confidence.DIRECT));
        }

        segmentMapper.selectList(new LambdaQueryWrapper<TranscriptSegment>()
                        .eq(TranscriptSegment::getVideoId, videoId)
                        .orderByAsc(TranscriptSegment::getSeq))
                .forEach(s -> list.add(new Evidence(EvidenceType.AUDIO,
                        s.getStartSec(), s.getEndSec(), s.getText(), Confidence.DIRECT)));

        frameMapper.selectList(new LambdaQueryWrapper<FrameRecord>()
                        .eq(FrameRecord::getVideoId, videoId)
                        .orderByAsc(FrameRecord::getFrameTime))
                .forEach(f -> {
                    if (f.getDescription() != null && !f.getDescription().isBlank()) {
                        list.add(new Evidence(EvidenceType.VISION,
                                f.getFrameTime(), f.getFrameTime(), f.getDescription(), Confidence.DIRECT));
                    }
                });

        // OCR 第三信源：画面文字/字幕，按 frameTime 升序，连续相同文本合并为一个区间，避免刷屏
        List<VideoOcr> ocrRows = videoOcrMapper.selectList(new LambdaQueryWrapper<VideoOcr>()
                .eq(VideoOcr::getVideoId, videoId)
                .orderByAsc(VideoOcr::getFrameTime));
        for (int i = 0; i < ocrRows.size(); ) {
            VideoOcr first = ocrRows.get(i);
            int j = i + 1;
            while (j < ocrRows.size()
                    && first.getText().equals(ocrRows.get(j).getText())
                    && Math.abs(ocrRows.get(j).getFrameTime() - first.getFrameTime()) <= 10) {
                j++;
            }
            list.add(new Evidence(EvidenceType.OCR, first.getFrameTime(),
                    ocrRows.get(j - 1).getFrameTime(), first.getText(), Confidence.DIRECT));
            i = j;
        }

        // 音视校正注记：画面字幕对专名权威于 ASR，作为审校提示追加在音/视之后
        correctionService.corrections(videoId).forEach(c ->
                list.add(new Evidence(EvidenceType.AUDIO, c.startSec(), c.endSec(),
                        "【音视校订】" + c.toAnnotation(), c.confidence())));

        return list;
    }
}