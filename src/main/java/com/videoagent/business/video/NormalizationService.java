package com.videoagent.business.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.AsrProperties;
import com.videoagent.config.LlmProperties;
import com.videoagent.middleware.llm.AsrProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实体归一化（去噪型修正，必做、可用 normalize.enabled 开关关闭）：
 * ASR 后接一次性轻量 LLM 修正明显乱码的已知实体（如 "GAME GARL'S BAND CRY"→"GIRLS BAND CRY"）。
 * 只改已确定对应正确实体的写法，不扩写、不概括，限定已知实体以控幻觉。
 * 逐视频一批调用，尽可能复用既有文本（确定性优先，非无可修正不重写）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NormalizationService {

    private static final int MAX_RETRY = 2;
    private static final long RETRY_BASE_MS = 500;

    private final AsrProperties asr;
    private final LlmProperties llm;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    private RestClient client() {
        if (restClient == null) {
            restClient = RestClient.builder()
                    .baseUrl(llm.deepseek().baseUrl())
                    .defaultHeader("Authorization", "Bearer " + llm.deepseek().apiKey())
                    .build();
        }
        return restClient;
    }

    /**
     * 归一化 ASR 分段中的已知实体乱码；未启用或调用失败时原样返回（不阻塞转录主流程）。
     */
    public List<AsrProvider.Segment> normalize(Long videoId, List<AsrProvider.Segment> segments) {
        if (!enabled() || segments == null || segments.isEmpty() || hasBlankText(segments)) {
            return segments;
        }
        try {
            return doNormalize(videoId, segments);
        } catch (Exception e) {
            // 归一化是增强型修正，失败不应拖垮转录，返回原文并告警
            log.warn("[asr:normalize] videoId={} 归一化失败，使用原文: {}", videoId, e.getMessage());
            return segments;
        }
    }

    private boolean enabled() {
        AsrProperties.Normalize n = asr.normalize();
        return n != null && Boolean.TRUE.equals(n.enabled());
    }

    private boolean hasBlankText(List<AsrProvider.Segment> segments) {
        return segments.stream().map(AsrProvider.Segment::text)
                .allMatch(t -> t == null || t.isBlank());
    }

    private List<AsrProvider.Segment> doNormalize(Long videoId, List<AsrProvider.Segment> segments)
            throws Exception {
        if (llm.deepseek().apiKey() == null || llm.deepseek().apiKey().isBlank()) {
            throw new BusinessException("未配置 DEEPSEEK_API_KEY，无法归一化实体");
        }
        StringBuilder input = new StringBuilder("{\"segments\":[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                input.append(',');
            }
            String text = segments.get(i).text() == null ? "" : segments.get(i).text();
            input.append("{\"seq\":").append(i).append(",\"text\":")
                    .append(objectMapper.writeValueAsString(text)).append('}');
        }
        input.append("]}");

        Map<Integer, String> corrected = callVlm(videoId, input.toString());
        if (corrected.isEmpty()) {
            return segments;
        }
        List<AsrProvider.Segment> out = new ArrayList<>(segments.size());
        int changed = 0;
        for (int i = 0; i < segments.size(); i++) {
            AsrProvider.Segment s = segments.get(i);
            String c = corrected.get(i);
            if (c != null && !c.isBlank() && !c.equals(s.text())) {
                out.add(new AsrProvider.Segment(s.startSec(), s.endSec(), c));
                changed++;
            } else {
                out.add(s);
            }
        }
        if (changed > 0) {
            log.info("[asr:normalize] videoId={} 共修正实体分段 {} 条", videoId, changed);
        }
        return out;
    }

    private Map<Integer, String> callVlm(Long videoId, String input) throws Exception {
        String prompt = """
                你是语音转写校正助手。下面给出某视频的语音转写分段。转写中的专有名词
                （人名、作品名、机构名、地名、专有术语等）可能被误转成同音错字或罗马拼音（乱码）。
                请：
                1. 仅修正你确定对应同一个正确实体的明显错误写法；
                2. 保留口语内容、语序、标点不变；不要扩写、不要补充原文没有的信息、不要概括；
                3. 不确定的整段原样返回；
                4. 没有需要修正的分段，其 text 原样返回。
                严格只输出一个 JSON 对象，不要任何额外说明或 markdown 代码块标记：
                {"segments":[{"seq":0,"text":"..."},{"seq":1,"text":"..."},...]}
                seq 与输入一一对应。输入如下：
                %s
                """.formatted(input);

        int attempt = 0;
        while (true) {
            try {
                return doCall(prompt);
            } catch (Exception e) {
                if (++attempt >= MAX_RETRY) {
                    throw e;
                }
                long backoff = RETRY_BASE_MS * attempt;
                log.warn("[asr:normalize] videoId={} 调用失败，第{}次重试 ({}ms): {}",
                        videoId, attempt, backoff, e.getMessage());
                Thread.sleep(backoff);
            }
        }
    }

    private Map<Integer, String> doCall(String prompt) throws Exception {
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
        return parseSegments(content);
    }

    private Map<Integer, String> parseSegments(String content) {
        Map<Integer, String> out = new java.util.LinkedHashMap<>();
        try {
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```(json)?", "").trim();
            }
            // 兼容直接返回数组或 {"segments":[...]}
            JsonNode node = objectMapper.readTree(cleaned);
            JsonNode arr = node.isArray() ? node : node.get("segments");
            if (arr == null || !arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                if (n.has("seq") && n.has("text")) {
                    out.put(n.get("seq").asInt(), n.get("text").asText());
                }
            }
        } catch (Exception e) {
            throw new BusinessException("归一化结果解析失败: " + e.getMessage());
        }
        return out;
    }
}