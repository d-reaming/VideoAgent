package com.videoagent.business.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.AsrProperties;
import com.videoagent.config.StorageProperties;
import com.videoagent.middleware.llm.AsrProvider;
import com.videoagent.middleware.util.FfmpegCommandRunner;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * 转写服务：ffmpeg 提音频 → ASR 双通道（本地 faster-whisper → 云端兜底）→ 分段入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final StorageProperties storage;
    private final AsrProperties asr;
    private final VideoMapper videoMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FfmpegCommandRunner ffmpegRunner;
    private final List<AsrProvider> asrProviders;
    private final NormalizationService normalizationService;
    private final StringRedisTemplate redisTemplate;

    public void process(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在: " + videoId);
        }
        Path audio = extractAudio(video);
        List<AsrProvider.Segment> segments = transcribeWithFallback(videoId, audio);
        segments = normalizationService.normalize(videoId, segments);
        saveSegments(videoId, segments);
    }

    /** ffmpeg 提取 16k 单声道 wav */
    private Path extractAudio(Video video) {
        try {
            Path audioDir = Paths.get(storage.audioDir());
            Files.createDirectories(audioDir);
            Path audio = audioDir.resolve(video.getId() + ".wav");
            // 已存在则跳过（重试场景）
            if (Files.exists(audio) && Files.size(audio) > 0) {
                return audio;
            }
            List<String> args = List.of("-y", "-i", video.getFilePath(),
                    "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", audio.toString());
            ffmpegRunner.runFfmpeg(args, 600);
            return audio;
        } catch (Exception e) {
            throw new BusinessException("音频提取失败: " + e.getMessage());
        }
    }

    /** 双通道降级：本地失败 → 云端兜底，降级点写 Redis 供前端展示 */
    private List<AsrProvider.Segment> transcribeWithFallback(Long videoId, Path audio) {
        String language = resolveLanguage();
        // 本地通道优先（LocalWhisperAsrProvider），云端兜底
        List<AsrProvider> providers = asrProviders.stream()
                .sorted(java.util.Comparator.comparing(p -> p.name().startsWith("local") ? 0 : 1))
                .toList();
        for (int i = 0; i < providers.size(); i++) {
            AsrProvider provider = providers.get(i);
            try {
                List<AsrProvider.Segment> result = provider.transcribe(audio, language);
                if (i > 0) {
                    log.warn("[asr] videoId={} 已降级到 {} ", videoId, provider.name());
                    redisTemplate.opsForValue().set("video-agent:degrade:" + videoId,
                            provider.name(), Duration.ofDays(7));
                }
                return result;
            } catch (Exception e) {
                log.warn("[asr] videoId={} {} 通道失败: {}", videoId, provider.name(), e.getMessage());
            }
        }
        throw new BusinessException("所有 ASR 通道均失败");
    }

    /**
     * 按语言模式解析目标语言：
     * AUTO → null（让 ASR 自动识别源语言，支持混语言）；FIXED → 固定目标语言。
     */
    private String resolveLanguage() {
        AsrProperties.Local local = asr.local();
        if (local.languageMode() != null && "FIXED".equalsIgnoreCase(local.languageMode())) {
            return local.language();
        }
        return null;
    }

    private void saveSegments(Long videoId, List<AsrProvider.Segment> segments) {
        segmentMapper.delete(new LambdaQueryWrapper<TranscriptSegment>()
                .eq(TranscriptSegment::getVideoId, videoId));
        for (int i = 0; i < segments.size(); i++) {
            AsrProvider.Segment s = segments.get(i);
            if (s.text() == null || s.text().isBlank()) {
                continue;
            }
            TranscriptSegment seg = new TranscriptSegment();
            seg.setVideoId(videoId);
            seg.setSeq(i);
            seg.setStartSec(s.startSec());
            seg.setEndSec(s.endSec());
            seg.setText(s.text());
            segmentMapper.insert(seg);
        }
        log.info("[transcribe] videoId={} segments={}", videoId, segments.size());
    }
}
