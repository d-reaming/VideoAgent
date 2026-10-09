package com.videoagent.business.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划 Agent（三角色之 Planner）：解析问题意图，通过强制工具调用输出结构化 {@link Plan}。
 * 总结/摘要类问题标记 needFullText=true，让执行 Agent 直接读全量转写，避免 topK 片段丢上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /** 规划失败时的兜底 Plan */
    private static final Plan FALLBACK = Plan.fallback();

    public Plan plan(String question, String questionType, String historyText) {
        String system = """
                你是视频问答的规划 Agent。面对用户问题，先拆解意图，再调用 submitPlan 输出检索规划。
                拆解维度：
                1. intent：问题意图。可选值包括 ABSTRACT(摘要/总结全文大意)、VISUAL(画面)、TEMPORAL(时间定位)、COMPLEX(对比/分析)、GENERAL(一般事实)。
                2. retrieveMode：检索偏好。TOP_K     任取   FULL_TEXT 需要全量转写；摘要类固定 FULL_TEXT，普通事实类 TOP_K。
                3. textTopK / frameTopK：语音/画面检索条数，按问题复杂度与画面相关性给 4-12 数值。
                4. keywords：2-5 个贴合视频内容的中文检索关键词（用于提高命中率），可为空。
                5. needFullText：是否需要全量转写（true 时执行 Agent 直接用全量转写作答，不再依赖片段检索）。
                不要直接回答用户问题，只输出规划结果。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        if (historyText != null && !historyText.isBlank()) {
            messages.add(SystemMessage.from("对话历史：\n" + historyText));
        }
        messages.add(UserMessage.from("问题类型：" + questionType + "\n问题：" + question));

        List<ToolSpecification> specs = List.of(ToolSpecification.builder()
                .name("submitPlan")
                .description("提交检索规划")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("intent")
                        .addStringProperty("retrieveMode")
                        .addIntegerProperty("textTopK")
                        .addIntegerProperty("frameTopK")
                        .addBooleanProperty("needFullText")
                        .required("intent", "retrieveMode", "textTopK", "frameTopK", "needFullText")
                        .build())
                .build());
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build());
            AiMessage ai = resp.aiMessage();
            if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
                String args = ai.toolExecutionRequests().get(0).arguments();
                Plan p = parse(args);
                log.info("[planner] intent={} mode={} textTopK={} frameTopK={} needFullText={} keywords={}",
                        p.intent(), p.retrieveMode(), p.textTopK(), p.frameTopK(), p.needFullText(), p.keywords());
                return p;
            }
            // 模型未走工具调用：尝试把文本解析为 JSON
            if (ai.text() != null) {
                Plan p = parse(ai.text());
                if (p != null) {
                    return p;
                }
            }
        } catch (Exception e) {
            log.warn("[planner] 规划失败，使用规则兜底: {}", e.getMessage());
        }
        return FALLBACK;
    }

    /** 反序列化 Plan，字段缺失/类型错误时兜底为默认值，避免一次字段异常破坏整体 */
    private Plan parse(String argsJson) {
        try {
            JsonNode root = objectMapper.readTree(argsJson);
            int textTopK = root.path("textTopK").asInt(8);
            int frameTopK = root.path("frameTopK").asInt(4);
            String intent = textOr(root, "intent", "GENERAL");
            String retrieveMode = textOr(root, "retrieveMode", "TOP_K");
            boolean needFullText = root.path("needFullText").asBoolean(false);
            List<String> keywords = new ArrayList<>();
            if (root.has("keywords") && root.get("keywords").isArray()) {
                root.get("keywords").forEach(k -> {
                    if (!k.asText().isBlank()) {
                        keywords.add(k.asText());
                    }
                });
            }
            return new Plan(intent, retrieveMode, textTopK, frameTopK, keywords, needFullText);
        } catch (JsonProcessingException e) {
            log.warn("[planner] 规划 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String textOr(JsonNode root, String field, String defaultVal) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? defaultVal : n.asText();
    }
}