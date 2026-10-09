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
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 验收 Agent（三角色之 Reviewer）：校验最终回答质量并给出回炉理由。
 * 输出 {pass: boolean, reason: string}，供执行 Agent 修正后重答（最多 MAX_REVISE_ROUNDS 轮）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent {

    private final ChatModel chatModel;

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\[\\d{2}:\\d{2}(?:-\\d{2}:\\d{2})?]");

    public record ReviewResult(boolean pass, String reason) {
    }

    /**
     * 校验回答是否充分作答、引用格式是否规范。
     * 视频 id 为空（全局问题）时跳过引用强制要求。
     */
    public ReviewResult review(String question, String answer, Long videoId) {
        boolean hasTimestamp = TIMESTAMP_PATTERN.matcher(answer).find();
        String system = """
                你是视频问答的验收 Agent。后台判断给定的回答是否合格，并输出 JSON {"pass": true/false, "reason": "说明"}。
                判定标准：
                1. 是否充分回答了用户问题（避免自相矛盾、答非所问、明显信息缺失）。
                2. 是否明确说明"视频中未找到相关讲解"当检索不到内容时，而非编造。
                3. reference 校验：回答引用的 [mm:ss] 必须来自给定检索片段；无依据断言且无法从检索修正时标记不得通过。
                只输出 JSON，不要输出其他内容。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        messages.add(UserMessage.from("问题：" + question + "\n\n回答：\n" + answer + "\n\n视频ID：" + videoId));
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            String text = resp.aiMessage().text();
            return parse(text);
        } catch (Exception e) {
            log.warn("[reviewer] 校验失败，默认通过: {}", e.getMessage());
            return new ReviewResult(true, "校验服务异常，按通过处理");
        }
    }

    private ReviewResult parse(String text) {
        // 兜底：未输出 JSON 时按通过处理
        if (text == null || !text.contains("{")) {
            return new ReviewResult(true, "reviewer 未返回结构化结果，按通过处理");
        }
        String json = text.substring(text.indexOf("{"), text.lastIndexOf("}") + 1);
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            boolean pass = root.path("pass").asBoolean(true);
            String reason = root.has("reason") ? root.get("reason").asText() : "";
            return new ReviewResult(pass, reason);
        } catch (JsonProcessingException e) {
            log.warn("[reviewer] 结果解析失败: {}", e.getMessage());
            return new ReviewResult(true, "reviewer JSON 解析失败，按通过处理");
        }
    }
}