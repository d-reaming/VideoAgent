package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.rag.VectorSearchService;
import com.videoagent.storage.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RAG 检索工具（供 Agent 调用）：图文联合检索，输出 JSON。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagSearchTool {

    private final VectorSearchService vectorSearchService;
    private final ObjectMapper objectMapper;

    /**
     * 语义检索视频内容（文本+画面）。
     *
     * @param question 检索问题
     * @param videoId  目标视频（null=全局）
     * @param textTopK 文本块数量
     * @param frameTopK 画面块数量
     */
    public String ragSearch(String question, Long videoId, int textTopK, int frameTopK) {
        try {
            List<VectorRepository.SearchHit> hits =
                    vectorSearchService.search(question, videoId, textTopK, frameTopK);
            List<Map<String, Object>> list = hits.stream().map(h -> Map.<String, Object>of(
                    "videoId", h.videoId(),
                    "type", h.type(),
                    "startSec", h.startSec() == null ? 0 : h.startSec(),
                    "endSec", h.endSec() == null ? 0 : h.endSec(),
                    "score", Math.round(h.score() * 1000) / 1000.0,
                    "content", h.content())).toList();
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new BusinessException("检索工具执行失败: " + e.getMessage());
        }
    }
}
