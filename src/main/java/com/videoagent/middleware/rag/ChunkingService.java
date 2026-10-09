package com.videoagent.middleware.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 切块策略（亮点 B）：
 * - 转写文本按「时间窗 + 语义」合并为块（目标 20~40s / 300 字内）
 * - 帧描述每帧独立成块（帧时间锚点）
 * - OCR 画面文字连续相同文本合并为块（字幕/屏显文字，type=OCR）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private static final int TARGET_WINDOW_SEC = 25;
    private static final int MAX_CHARS = 300;

    private final TranscriptSegmentMapper segmentMapper;
    private final FrameRecordMapper frameMapper;
    private final VideoOcrMapper videoOcrMapper;
    private final ObjectMapper objectMapper;

    public List<Chunk> buildChunks(Long videoId) {
        List<Chunk> chunks = new ArrayList<>();
        chunks.addAll(textChunks(videoId));
        chunks.addAll(frameChunks(videoId));
        chunks.addAll(ocrChunks(videoId));
        return chunks;
    }

    private List<Chunk> textChunks(Long videoId) {
        List<TranscriptSegment> segments = segmentMapper.selectList(
                new LambdaQueryWrapper<TranscriptSegment>()
                        .eq(TranscriptSegment::getVideoId, videoId)
                        .orderByAsc(TranscriptSegment::getSeq));

        List<Chunk> chunks = new ArrayList<>();
        List<TranscriptSegment> buffer = new ArrayList<>();
        for (TranscriptSegment seg : segments) {
            buffer.add(seg);
            int window = seg.getEndSec() - (buffer.isEmpty() ? seg.getStartSec() : buffer.get(0).getStartSec());
            int chars = buffer.stream().mapToInt(s -> s.getText().length()).sum();
            if (window >= TARGET_WINDOW_SEC || chars >= MAX_CHARS) {
                chunks.add(toChunk(videoId, "TEXT", chunks.size(), buffer));
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            chunks.add(toChunk(videoId, "TEXT", chunks.size(), buffer));
        }
        return chunks;
    }

    private Chunk toChunk(Long videoId, String type, int seq, List<TranscriptSegment> buffer) {
        int start = buffer.get(0).getStartSec();
        int end = buffer.get(buffer.size() - 1).getEndSec();
        String content = buffer.stream()
                .map(s -> "[" + fmt(s.getStartSec()) + "-" + fmt(s.getEndSec()) + "] " + s.getText())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return new Chunk(String.valueOf(videoId), type, seq, start, end, content, null);
    }

    private List<Chunk> frameChunks(Long videoId) {
        List<FrameRecord> frames = frameMapper.selectList(
                new LambdaQueryWrapper<FrameRecord>()
                        .eq(FrameRecord::getVideoId, videoId)
                        .orderByAsc(FrameRecord::getFrameTime));
        List<Chunk> chunks = new ArrayList<>();
        int seq = 0;
        for (FrameRecord frame : frames) {
            if (frame.getDescription() == null || frame.getDescription().isBlank()) {
                continue;
            }
            String content = "【画面@" + fmt(frame.getFrameTime()) + "】" + frame.getDescription();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("imageUrl", frame.getImagePath());
            chunks.add(new Chunk(String.valueOf(videoId), "FRAME", seq++,
                    frame.getFrameTime(), frame.getFrameTime() + 1, content,
                    toJson(meta)));
        }
        return chunks;
    }

    /** OCR 画面文字块：连续相同文本合并为一块（字幕/屏显文字），独立 seq */
    private List<Chunk> ocrChunks(Long videoId) {
        List<VideoOcr> rows = videoOcrMapper.selectList(
                new LambdaQueryWrapper<VideoOcr>()
                        .eq(VideoOcr::getVideoId, videoId)
                        .orderByAsc(VideoOcr::getFrameTime));
        List<Chunk> chunks = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < rows.size(); ) {
            VideoOcr first = rows.get(i);
            int j = i + 1;
            while (j < rows.size()
                    && first.getText().equals(rows.get(j).getText())
                    && Math.abs(rows.get(j).getFrameTime() - first.getFrameTime()) <= 10) {
                j++;
            }
            String content = "【字幕@" + fmt(first.getFrameTime()) + "】" + first.getText();
            chunks.add(new Chunk(String.valueOf(videoId), "OCR", seq++,
                    first.getFrameTime(), rows.get(j - 1).getFrameTime(), content, null));
            i = j;
        }
        return chunks;
    }

    private String fmt(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    /** 用 Jackson 序列化 metadata，确保路径等含反斜杠的内容被正确 JSON 转义（PG jsonb 解析） */
    private String toJson(Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 序列化失败: " + e.getMessage(), e);
        }
    }
}
