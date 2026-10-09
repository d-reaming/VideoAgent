package com.videoagent.business.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.business.evidence.Confidence;
import com.videoagent.config.AsrProperties;
import com.videoagent.config.LlmProperties;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.VideoOcr;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoOcrMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 音视校正（S4，去噪型修正，必做、可用 correct.enabled 开关关闭）：
 * 用画面文字权威源（OCR 字幕/屏显文字优先，无 OCR 时回退帧描述）校正同时间戳
 * ASR 乱码专名，产出 "时间 → 画面权威正确文本" 映射。
 * <p>
 * 流水线为 转录→抽帧 顺序，转录时帧尚不存在，故校正为懒计算：由 GlobalContextTool 消费时
 * 触发一次 LLM 校正并缓存到 Redis（TTL 1h），避免每次问答重复调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorrectionService {

    private static final String CACHE_KEY = "video-agent:correction:";
    private static final Duration TTL = Duration.ofHours(1);
    /** 帧时间与 ASR 分段对齐窗口（秒） */
    private static final int ALIGN_WINDOW = 3;

    private final AsrProperties asr;
    private final LlmProperties llm;
    private final TranscriptSegmentMapper segmentMapper;
    private final FrameRecordMapper frameMapper;
    private final VideoOcrMapper videoOcrMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    /** 一条音视校正：ASR 原文本 → 画面文字权威正确写法 */
    public record Correction(int startSec, int endSec, String asr, String corrected, Confidence confidence) {
        public String toAnnotation() {
            return "语音【" + asr + "】→ 字幕权威写法【" + corrected + "】";
        }
    }

    /**
     * 获取某视频的音视校正注记列表（懒计算 + Redis 缓存）。
     * 未启用/无帧字幕/无匹配时返回空列表。
     */
    public List<Correction> corrections(Long videoId) {
        if (!enabled()) {
            return List.of();
        }
        String key = CACHE_KEY + videoId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return objectMapper.readValue(cached, new TypeReference<List<Correction>>() {
                });
            }
        } catch (Exception e) {
            log.warn("[asr:correct] videoId={} 读缓存失败，回源计算: {}", videoId, e.getMessage());
        }
        List<Correction> result = compute(videoId);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), TTL);
        } catch (Exception e) {
            log.warn("[asr:correct] videoId={} 写缓存失败: {}", videoId, e.getMessage());
        }
        return result;
    }

    private boolean enabled() {
        AsrProperties.Correct c = asr.correct();
        return c != null && Boolean.TRUE.equals(c.enabled());
    }

    private List<Correction> compute(Long videoId) {
        List<TranscriptSegment> segments = segmentMapper.selectList(new LambdaQueryWrapper<TranscriptSegment>()
                .eq(TranscriptSegment::getVideoId, videoId)
                .orderByAsc(TranscriptSegment::getSeq));
        if (segments.isEmpty()) {
            return List.of();
        }
        // 画面文字权威源：OCR 字幕/屏显文字优先（精确），帧描述回退（描述性）
        List<VideoOcr> ocrRows = videoOcrMapper.selectList(new LambdaQueryWrapper<VideoOcr>()
                .eq(VideoOcr::getVideoId, videoId)
                .orderByAsc(VideoOcr::getFrameTime));
        List<FrameRecord> frames = frameMapper.selectList(new LambdaQueryWrapper<FrameRecord>()
                .eq(FrameRecord::getVideoId, videoId)
                .orderByAsc(FrameRecord::getFrameTime));
        List<FrameRecord> textFrames = frames.stream()
                .filter(f -> f.getDescription() != null && !f.getDescription().isBlank())
                .toList();
        if (ocrRows.isEmpty() && textFrames.isEmpty()) {
            return List.of();
        }

        List<Integer> aligned = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment s = segments.get(i);
            int t = s.getStartSec();
            boolean hit = ocrRows.stream()
                    .anyMatch(o -> Math.abs(o.getFrameTime() - t) <= ALIGN_WINDOW)
                    || textFrames.stream()
                    .anyMatch(f -> Math.abs(f.getFrameTime() - t) <= ALIGN_WINDOW);
            if (hit) {
                aligned.add(i);
            }
        }
        if (aligned.isEmpty()) {
            return List.of();
        }
        return llmCorrect(videoId, segments, textFrames, ocrRows, aligned);
    }

    /** 批量 LLM 校正：逐一分段用同窗口画面文字（OCR 优先）作为专名权威参照，输出校正结果 */
    private List<Correction> llmCorrect(Long videoId, List<TranscriptSegment> segments,
                                        List<FrameRecord> textFrames, List<VideoOcr> ocrRows,
                                        List<Integer> aligned) {
        // 组装输入：seg + 同窗口画面文字（OCR 优先，无 OCR 回退帧描述）
        StringBuilder input = new StringBuilder("{\"pairs\":[");
        for (int idx = 0; idx < aligned.size(); idx++) {
            int i = aligned.get(idx);
            TranscriptSegment s = segments.get(i);
            if (idx > 0) {
                input.append(',');
            }
            String ocrTexts = ocrRows.stream()
                    .filter(o -> Math.abs(o.getFrameTime() - s.getStartSec()) <= ALIGN_WINDOW)
                    .map(VideoOcr::getText)
                    .reduce((a, b) -> a + "；" + b)
                    .orElse("");
            String frameTexts = textFrames.stream()
                    .filter(f -> Math.abs(f.getFrameTime() - s.getStartSec()) <= ALIGN_WINDOW)
                    .map(FrameRecord::getDescription)
                    .reduce((a, b) -> a + "；" + b)
                    .orElse("");
            // OCR 文字权威于 VLM 描述：命中 OCR 时以其为准
            String authoritative = ocrTexts.isBlank() ? frameTexts : ocrTexts;
            input.append("{\"seq\":").append(idx)
                    .append(",\"asr\":").append(quote(s.getText()))
                    .append(",\"frame\":").append(quote(authoritative)).append('}');
        }
        input.append("]}");

        List<Map<String, Object>> parsed = callLlm(videoId, input.toString(), aligned.size());

        List<Correction> out = new ArrayList<>();
        for (int k = 0; k < parsed.size() && k < aligned.size(); k++) {
            Map<String, Object> m = parsed.get(k);
            String asr = str(m.get("asr"));
            String corrected = str(m.get("corrected"));
            if (corrected == null || corrected.isBlank() || corrected.equals(asr)) {
                continue; // 画面字幕无法确认更优写法，跳过
            }
            TranscriptSegment s = segments.get(aligned.get(k));
            out.add(new Correction(s.getStartSec(), s.getEndSec(), asr,
                    corrected, Confidence.INFERENCE));
        }
        if (!out.isEmpty()) {
            log.info("[asr:correct] videoId={} 生成音视校正 {} 条", videoId, out.size());
        }
        return out;
    }

    private List<Map<String, Object>> callLlm(Long videoId, String input, int size) {
        if (llm.deepseek().apiKey() == null || llm.deepseek().apiKey().isBlank()) {
            log.warn("[asr:correct] videoId={} 未配置 DEEPSEEK_API_KEY，跳过音视校正", videoId);
            return List.of();
        }
        String prompt = """
                你是视频音视校对助手。视频画面上有烧录字幕或屏显文字（frame），它对专有名词的写法
                比语音识别（asr）更权威。下面是若干对：{ 语音转写 asr, 同时刻画面字幕 frame }。
                请为每一对判断：asr 中是否存在因同音/识别错误而写错的专有名词（人名/作品名/机构/术语），
                且能从 frame 的文字内容中确定其正确写法。
                若存在：corrected 填"确定的正确写法"（尽量保留 asr 中非专名的原貌，仅替换专名）；
                否则 corrected 置空字符串表示不修改。
                禁止扩写、禁止添加 asr 中没有的信息。
                严格只输出一个 JSON 数组，不要任何额外说明或 markdown 标记：
                [{"seq":0,"asr":"...","corrected":"..."},{"seq":1,...},...]
                seq 与输入一一对应。输入如下：
                %s
                """.formatted(input);
        try {
            Map<String, Object> body = Map.of(
                    "model", llm.deepseek().model(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.0);
            String response = client().post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```(json)?", "").trim();
            }
            return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("[asr:correct] videoId={} 校正调用失败: {}", videoId, e.getMessage());
            return List.of();
        }
    }

    private RestClient client() {
        if (restClient == null) {
            restClient = RestClient.builder()
                    .baseUrl(llm.deepseek().baseUrl())
                    .defaultHeader("Authorization", "Bearer " + llm.deepseek().apiKey())
                    .build();
        }
        return restClient;
    }

    private String quote(Object s) {
        try {
            return objectMapper.writeValueAsString(s == null ? "" : s.toString());
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private String str(Object v) {
        return v == null ? null : v.toString();
    }
}