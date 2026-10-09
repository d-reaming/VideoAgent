package com.videoagent.business.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频元数据工具：供 Agent 查询视频信息（标题/时长/内容概览）。
 */
@Component
@RequiredArgsConstructor
public class VideoMetaTool {

    private final VideoMapper videoMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FrameRecordMapper frameMapper;
    private final ObjectMapper objectMapper;

    /** 元数据查询 */
    public String videoMeta(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在: " + videoId);
        }
        Long segCount = segmentMapper.selectCount(new LambdaQueryWrapper<TranscriptSegment>()
                .eq(TranscriptSegment::getVideoId, videoId));
        Long frameCount = frameMapper.selectCount(new LambdaQueryWrapper<FrameRecord>()
                .eq(FrameRecord::getVideoId, videoId));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("videoId", video.getId());
        meta.put("title", video.getTitle());
        meta.put("durationSec", video.getDurationSec());
        meta.put("status", video.getStatus());
        meta.put("segmentCount", segCount);
        meta.put("frameCount", frameCount);
        return toJson(meta);
    }

    /** 时间轴概览：转写分段前 N 条，辅助时间类问题 */
    public String timeline(Long videoId, int limit) {
        List<TranscriptSegment> segments = segmentMapper.selectList(new LambdaQueryWrapper<TranscriptSegment>()
                .eq(TranscriptSegment::getVideoId, videoId)
                .orderByAsc(TranscriptSegment::getSeq)
                .last("limit " + limit));
        List<Map<String, Object>> list = segments.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startSec", s.getStartSec());
            m.put("endSec", s.getEndSec());
            m.put("text", s.getText());
            return m;
        }).toList();
        return toJson(list);
    }

    /** 全量转写：拉取该视频所有转写分段，供摘要/总结类问题基于全文作答（而非 topK 片段） */
    public String fullText(Long videoId) {
        List<TranscriptSegment> segments = segmentMapper.selectList(new LambdaQueryWrapper<TranscriptSegment>()
                .eq(TranscriptSegment::getVideoId, videoId)
                .orderByAsc(TranscriptSegment::getSeq));
        if (segments.isEmpty()) {
            return "该视频暂无转写内容。";
        }
        StringBuilder sb = new StringBuilder();
        for (TranscriptSegment s : segments) {
            sb.append('[').append(fmt(s.getStartSec())).append('-').append(fmt(s.getEndSec())).append("] ")
                    .append(s.getText()).append('\n');
        }
        return sb.toString().strip();
    }

    private String fmt(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BusinessException("序列化失败: " + e.getMessage());
        }
    }
}
