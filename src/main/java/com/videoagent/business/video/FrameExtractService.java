package com.videoagent.business.video;

import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.OcrProperties;
import com.videoagent.config.StorageProperties;
import com.videoagent.middleware.llm.OcrClient;
import com.videoagent.middleware.llm.VisionClient;
import com.videoagent.middleware.util.FfmpegCommandRunner;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.VideoOcr;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.VideoMapper;
import com.videoagent.storage.mapper.VideoOcrMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键帧提取 + 视觉描述 + OCR 文字识别（三通道之二/三）：
 * ffmpeg scene 检测抽帧 → 解析帧时间戳 → Qwen-VL 逐帧描述 + RapidOCR 逐帧文字提取 → 入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameExtractService {

    /** 最大关键帧数（控制视觉模型调用成本） */
    private static final int MAX_FRAMES = 30;
    /** scene 变化阈值 */
    private static final double SCENE_THRESHOLD = 0.3;

    private static final Pattern PTS_PATTERN = Pattern.compile("pts_time:([0-9.]+)");

    private final StorageProperties storage;
    private final VideoMapper videoMapper;
    private final FrameRecordMapper frameMapper;
    private final VideoOcrMapper videoOcrMapper;
    private final FfmpegCommandRunner ffmpegRunner;
    private final VisionClient visionClient;
    private final OcrClient ocrClient;
    private final ExecutorService frameDescribeExecutor;

    public void process(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在: " + videoId);
        }
        // 无视频流（纯音频/仅语音）：跳过分帧与视觉描述/OCR，仅保留语音问答
        if (video.getFilePath() == null || video.getFilePath().isBlank()
                || !ffmpegRunner.hasVideoStream(Path.of(video.getFilePath()))) {
            log.info("[frame] videoId={} 无视频流，跳过抽帧/视觉描述/OCR", videoId);
            return;
        }
        List<Double> frameTimes = extractFrames(video);
        List<FrameRecord> records = saveFrameRecords(videoId, frameTimes);
        runFrameProcessors(records);
    }

    /** 视觉描述与 OCR 文字提取并行执行（二者互不依赖，共享帧图） */
    private void runFrameProcessors(List<FrameRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> describeFrames(records), frameDescribeExecutor),
                CompletableFuture.runAsync(() -> ocrFrames(records), frameDescribeExecutor)
        ).join();
    }

    /** ffmpeg scene 检测抽帧，解析 stderr 中的 pts_time 获取帧时间 */
    private List<Double> extractFrames(Video video) {
        try {
            Path frameDir = Paths.get(storage.framesDir(), String.valueOf(video.getId()));
            Files.createDirectories(frameDir);
            String outPattern = frameDir.resolve("frame_%03d.jpg").toString();

            List<String> args = List.of("-y",
                    "-i", video.getFilePath(),
                    "-vf", "select='gt(scene," + SCENE_THRESHOLD + ")',showinfo",
                    "-vsync", "vfr", "-q:v", "2",
                    // 强制 JPEG 标准 JPEG 色彩范围，避免非标准 YUV 打开编码器失败(-22)；须置于 -i 之后作输出选项
                    "-pix_fmt", "yuvj420p",
                    outPattern);
            String output = ffmpegRunner.runFfmpeg(args, 900);

            List<Double> times = new ArrayList<>();
            Matcher matcher = PTS_PATTERN.matcher(output);
            while (matcher.find()) {
                times.add(Double.parseDouble(matcher.group(1)));
            }
            if (times.isEmpty()) {
                // scene 无变化（如纯音频/静止画面）：按固定间隔兜底
                int duration = video.getDurationSec() == null || video.getDurationSec() == 0
                        ? 0 : video.getDurationSec();
                for (int t = 5; t < duration; t += 15) {
                    times.add((double) t);
                }
                extractByInterval(video, frameDir, times);
            }
            return limitFrames(times);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("关键帧提取失败: " + e.getMessage());
        }
    }

    /** 兜底：scene 未检出时按固定间隔抽帧 */
    private void extractByInterval(Video video, Path frameDir, List<Double> times) {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("-y", "-i", video.getFilePath(), "-q:v", "2", "-pix_fmt", "yuvj420p"));
        for (int i = 0; i < times.size(); i++) {
            int t = times.get(i).intValue();
            args.addAll(List.of("-ss", String.valueOf(t), "-frames:v", "1",
                    frameDir.resolve("frame_" + String.format("%03d", i) + ".jpg").toString()));
        }
        ffmpegRunner.runFfmpeg(args, 900);
    }

    /** 帧数超限时按时间均匀采样，控制视觉模型调用成本 */
    private List<Double> limitFrames(List<Double> times) {
        if (times.size() <= MAX_FRAMES) {
            return times;
        }
        List<Double> sampled = new ArrayList<>();
        double step = times.size() / (double) MAX_FRAMES;
        for (int i = 0; i < MAX_FRAMES; i++) {
            sampled.add(times.get((int) Math.floor(i * step)));
        }
        return sampled;
    }

    private List<FrameRecord> saveFrameRecords(Long videoId, List<Double> frameTimes) {
        // 断点续传/失败重试幂等：删除前先按 frameTime 捕获既有非空描述，重建后复用，
        // 避免对每帧重复调用昂贵的 Qwen-VL。抽帧 scene 检测确定性输出，重跑帧集一致，可按 frameTime 匹配。
        Map<Integer, String> descByTime = new HashMap<>();
        List<FrameRecord> oldRecords = frameMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrameRecord>()
                        .eq(FrameRecord::getVideoId, videoId));
        for (FrameRecord old : oldRecords) {
            if (old.getDescription() != null && !old.getDescription().isBlank()) {
                descByTime.put(old.getFrameTime(), old.getDescription());
            }
        }
        frameMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrameRecord>()
                .eq(FrameRecord::getVideoId, videoId));
        List<FrameRecord> records = new ArrayList<>();
        for (int i = 0; i < frameTimes.size(); i++) {
            FrameRecord record = new FrameRecord();
            record.setVideoId(videoId);
            int frameTime = (int) Math.round(frameTimes.get(i));
            record.setFrameTime(frameTime);
            record.setImagePath(Paths.get(storage.framesDir(), String.valueOf(videoId),
                    "frame_" + String.format("%03d", i) + ".jpg").toString());
            // 复用同 frameTime 的既有描述，避免重复调用视觉模型
            String prev = descByTime.get(frameTime);
            if (prev != null) {
                record.setDescription(prev);
            }
            frameMapper.insert(record);
            records.add(record);
        }
        return records;
    }

    /** 并行调用视觉模型逐帧描述（并发数由 frameDescribeExecutor 决定）；单帧失败不阻断整批 */
    private void describeFrames(List<FrameRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = records.stream()
                .map(rec -> CompletableFuture.runAsync(() -> describeOne(rec), frameDescribeExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("[frame] videoId={} frames={}", records.get(0).getVideoId(), records.size());
    }

    private void describeOne(FrameRecord record) {
        // 断点续传幂等守卫：已存在非空描述则直接复用，不再调用昂贵的 Qwen-VL
        if (record.getDescription() != null && !record.getDescription().isBlank()) {
            log.info("[frame] frameId={} 复用既有描述，跳过视觉调用", record.getId());
            return;
        }
        try {
            String desc = visionClient.describeFrame(Paths.get(record.getImagePath()));
            record.setDescription(desc);
            frameMapper.updateById(record);
        } catch (Exception e) {
            // 单帧失败不阻断整批（保留空描述，向量化时跳过）
            log.warn("[frame] 描述失败 frameId={} err={}", record.getId(), e.getMessage());
        }
    }

    /**
     * OCR 逐帧文字提取（第三信源：字幕/屏显文字）。
     * 幂等仿 saveFrameRecords：按 frameTime 复用既有 OCR 行，重跑不重复调用 RapidOCR；
     * OCR 通道未启用或单帧失败仅告警，不阻断流水线。
     */
    private void ocrFrames(List<FrameRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        Long videoId = records.get(0).getVideoId();
        if (!ocrClient.enabled()) {
            log.info("[ocr] videoId={} OCR 通道未启用，跳过文字提取", videoId);
            return;
        }
        Map<Integer, List<VideoOcr>> oldByTime = new HashMap<>();
        for (VideoOcr old : videoOcrMapper.selectList(new LambdaQueryWrapper<VideoOcr>()
                .eq(VideoOcr::getVideoId, videoId))) {
            oldByTime.computeIfAbsent(old.getFrameTime(), k -> new ArrayList<>()).add(old);
        }
        videoOcrMapper.delete(new LambdaQueryWrapper<VideoOcr>().eq(VideoOcr::getVideoId, videoId));

        int total = 0;
        for (FrameRecord rec : records) {
            List<VideoOcr> prev = oldByTime.get(rec.getFrameTime());
            if (prev != null && !prev.isEmpty()) {
                // 复用既有 OCR 行，避免重跑时重复调用 OCR
                for (VideoOcr p : prev) {
                    p.setId(null);
                    videoOcrMapper.insert(p);
                }
                total += prev.size();
                continue;
            }
            try {
                List<OcrClient.OcrItem> items = ocrClient.ocr(Paths.get(rec.getImagePath()));
                for (OcrClient.OcrItem item : items) {
                    VideoOcr row = new VideoOcr();
                    row.setVideoId(videoId);
                    row.setFrameTime(rec.getFrameTime());
                    row.setText(item.text());
                    row.setConfidence(BigDecimal.valueOf(item.confidence()));
                    videoOcrMapper.insert(row);
                }
                total += items.size();
            } catch (Exception e) {
                // 单帧失败不阻断整批（该帧无 OCR 行，全局上下文/检索时跳过）
                log.warn("[ocr] 识别失败 frameId={} err={}", rec.getId(), e.getMessage());
            }
        }
        log.info("[ocr] videoId={} ocrItems={}", videoId, total);
    }
}
