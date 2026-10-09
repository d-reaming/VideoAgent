package com.videoagent.middleware.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding 客户端：通义 text-embedding-v3（DashScope OpenAI 兼容接口，1024 维）。
 */
@Slf4j
@Component
public class EmbeddingService {

    // DashScope text-embedding-v3 单次批最大 10 条
    private static final int BATCH_SIZE = 10;
    // 对瞬时网络抖动(Connection reset)做重试
    private static final int MAX_RETRY = 3;
    private static final long RETRY_BASE_MS = 500;

    private final LlmProperties llm;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public EmbeddingService(LlmProperties llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(llm.dashscope().baseUrl())
                .defaultHeader("Authorization", "Bearer " + llm.dashscope().apiKey())
                .build();
    }

    /** 批量文本向量化 */
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    /** 单条文本向量化 */
    public float[] embed(String text) {
        List<float[]> list = embedBatch(List.of(text));
        return list.get(0);
    }

    private List<float[]> embedBatch(List<String> texts) {
        int attempt = 0;
        while (true) {
            try {
                return doEmbed(texts);
            } catch (Exception e) {
                if (++attempt >= MAX_RETRY) {
                    throw new BusinessException("Embedding 调用失败(重试" + MAX_RETRY + "次): " + e.getMessage());
                }
                long backoff = RETRY_BASE_MS * attempt;
                log.warn("[embedding] 调用失败，第{}次重试 ({}ms): {}", attempt, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("Embedding 重试被中断");
                }
            }
        }
    }

    private List<float[]> doEmbed(List<String> texts) throws Exception {
        Map<String, Object> body = Map.of(
                "model", llm.dashscope().embeddingModel(),
                "input", texts,
                "dimensions", llm.dashscope().embeddingDim());
        String response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        JsonNode data = root.at("/data");
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode node : data) {
            float[] vec = new float[node.get("embedding").size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) node.get("embedding").get(i).asDouble();
            }
            vectors.add(vec);
        }
        log.info("[embedding] DashScope 调用成功 texts={}", texts.size());
        return vectors;
    }
}
