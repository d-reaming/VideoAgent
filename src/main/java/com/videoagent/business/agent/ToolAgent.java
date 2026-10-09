package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具执行器（Agent 工具注册表）：按名称分发到具体工具。
 */
@Component
@RequiredArgsConstructor
public class ToolAgent {

    private final RagSearchTool ragSearchTool;
    private final VideoMetaTool videoMetaTool;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public String execute(String toolName, String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);
            return switch (toolName) {
                case "ragSearch" -> ragSearchTool.ragSearch(
                        str(args, "question"),
                        longArg(args, "videoId"),
                        intArg(args, "textTopK", 8),
                        intArg(args, "frameTopK", 4));
                case "videoMeta" -> videoMetaTool.videoMeta(requiredLong(args, "videoId"));
                case "timeline" -> videoMetaTool.timeline(
                        requiredLong(args, "videoId"), intArg(args, "limit", 10));
                case "fullText" -> videoMetaTool.fullText(requiredLong(args, "videoId"));
                default -> throw new BusinessException("未知工具: " + toolName);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("工具参数解析失败: " + e.getMessage());
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Long longArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        return Long.valueOf(String.valueOf(v));
    }

    private Long requiredLong(Map<String, Object> args, String key) {
        Long v = longArg(args, key);
        if (v == null) {
            throw new BusinessException("工具缺少参数: " + key);
        }
        return v;
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        return v == null ? defaultValue : (int) Double.parseDouble(String.valueOf(v));
    }
}
