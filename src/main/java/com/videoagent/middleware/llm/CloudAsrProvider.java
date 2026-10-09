package com.videoagent.middleware.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.AsrProperties;
import com.videoagent.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 云端 ASR 兜底：通义 qwen-audio-turbo（DashScope OpenAI 兼容接口，音频 base64 输入）。
 * 说明：生产级长音频建议走 Paraformer 文件转写（需 OSS），此处为可演示的轻量实现。
 */
@Slf4j
@Component
public class CloudAsrProvider implements AsrProvider {

    private static final String MODEL = "qwen-audio-turbo";

    private final LlmProperties llm;
    private final AsrProperties asr;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public CloudAsrProvider(LlmProperties llm, AsrProperties asr, ObjectMapper objectMapper) {
        this.llm = llm;
        this.asr = asr;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(llm.dashscope().baseUrl())
                .defaultHeader("Authorization", "Bearer " + llm.dashscope().apiKey())
                .build();
    }

    @Override
    public String name() {
        return "cloud-qwen-audio";
    }

    @Override
    public List<Segment> transcribe(Path audioPath, String language) {
        if (!Boolean.TRUE.equals(asr.cloud().enabled())) {
            throw new BusinessException("云端 ASR 未启用(CLOUD_ASR_ENABLED=false)");
        }
        if (llm.dashscope().apiKey() == null || llm.dashscope().apiKey().isBlank()) {
            throw new BusinessException("云端 ASR 未配置 DASHSCOPE_API_KEY");
        }
        try {
            byte[] bytes = Files.readAllBytes(audioPath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String prompt = """
                    请完整转写这段音频，只输出 JSON 数组（不要任何额外说明），格式：
                    [{"start": 0, "end": 3, "text": "..."}, ...]
                    start/end 为秒数。语言：%s。
                    """.formatted(language == null || language.isBlank() ? "自动识别（可能是混合语言，请按实际语种转写）" : language);

            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", prompt),
                                    Map.of("type", "input_audio",
                                            "input_audio", Map.of("format", "wav", "data", base64))))));

            String response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();
            return parseSegments(content);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("云端转写异常: " + e.getMessage());
        }
    }

    private List<Segment> parseSegments(String content) {
        try {
            // 清理可能存在的 ```json 包裹
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```(json)?", "").trim();
            }
            int startIdx = cleaned.indexOf('[');
            int endIdx = cleaned.lastIndexOf(']');
            if (startIdx < 0 || endIdx < startIdx) {
                throw new BusinessException("云端结果非 JSON 数组");
            }
            List<Segment> segments = new ArrayList<>();
            JsonNode array = objectMapper.readTree(cleaned.substring(startIdx, endIdx + 1));
            for (JsonNode node : array) {
                int start = node.has("start") ? (int) Math.round(node.get("start").asDouble()) : 0;
                int end = node.has("end") ? (int) Math.round(node.get("end").asDouble()) : start;
                segments.add(new Segment(start, end, node.get("text").asText()));
            }
            return segments;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("云端转写结果解析失败: " + e.getMessage());
        }
    }
}
