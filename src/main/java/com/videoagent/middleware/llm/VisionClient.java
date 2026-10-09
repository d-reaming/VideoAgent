package com.videoagent.middleware.llm;

import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 视觉模型客户端：通义 Qwen-VL（DashScope OpenAI 兼容接口）生成帧描述。
 */
@Slf4j
@Component
public class VisionClient {

    // 对瞬时网络抖动(Connection reset/5xx)做指数退避重试
    private static final int MAX_RETRY = 3;
    private static final long RETRY_BASE_MS = 500;

    private final LlmProperties llm;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public VisionClient(LlmProperties llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(llm.dashscope().baseUrl())
                .defaultHeader("Authorization", "Bearer " + llm.dashscope().apiKey())
                .build();
    }

    /**
     * 描述视频帧：输出适合检索的画面语义描述（含主体/场景/文字/图表等）。
     */
    public String describeFrame(Path imagePath) {
        if (llm.dashscope().apiKey() == null || llm.dashscope().apiKey().isBlank()) {
            throw new BusinessException("未配置 DASHSCOPE_API_KEY，无法生成帧描述");
        }
        int attempt = 0;
        while (true) {
            try {
                return doDescribeFrame(imagePath);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                if (++attempt >= MAX_RETRY) {
                    throw new BusinessException("帧描述生成失败(重试" + MAX_RETRY + "次): " + e.getMessage());
                }
                long backoff = RETRY_BASE_MS * attempt;
                log.warn("[vision] 帧描述调用失败，第{}次重试 ({}ms): {}", attempt, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("帧描述重试被中断");
                }
            }
        }
    }

    private String doDescribeFrame(Path imagePath) throws Exception {
        byte[] bytes = Files.readAllBytes(imagePath);
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);

        String prompt = """
                你是视频关键帧描述器。请用中文简洁描述这一帧画面，聚焦：
                1. 画面主体与人物动作
                2. 场景与环境
                3. 屏幕上可见的文字、标题、图表内容
                4. 与讲解内容的关联（如正在演示某操作）
                输出 2-3 句话，不要推测画面之外的信息。
                """;

        Map<String, Object> body = Map.of(
                "model", llm.dashscope().visionModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", prompt),
                                Map.of("type", "image_url",
                                        "image_url", Map.of("url", dataUrl))))),
                "max_tokens", 300);

        String response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        String content = root.at("/choices/0/message/content").asText();
        log.debug("[vision] frame={} desc={}", imagePath.getFileName(), content);
        return content.trim();
    }
}
