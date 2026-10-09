package com.videoagent.business.agent;

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

/**
 * 回答 Agent（快通道单轮生成）：基于检索上下文生成带 [mm:ss] 引用的最终回答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerAgent {

    private final ChatModel chatModel;

    public String generate(String question, String context, String historyText) {
        String system = """
                你是视频内容问答助手。基于给定的「视频检索上下文」回答用户问题。
                回答要求：
                1. 引用语音讲解内容时标注 [mm:ss]（时间来自上下文，如 [01:23]）
                2. 引用画面信息时标注 [mm:ss]（画面）
                3. 只依据检索上下文作答；上下文没有的信息，明确说明"视频中未找到相关讲解"
                4. 保持简洁准确，中文回答
                检索上下文中每行以 [mm:ss-mm:ss] 开头表示时间范围，格式示例：
                [00:12-00:35] 语音内容...
                【画面@01:23】画面描述...
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        if (historyText != null && !historyText.isBlank()) {
            messages.add(SystemMessage.from("对话历史：\n" + historyText));
        }
        messages.add(UserMessage.from("检索上下文：\n" + context + "\n\n问题：" + question));
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            return resp.aiMessage().text();
        } catch (Exception e) {
            throw new com.videoagent.access.exception.BusinessException("回答生成失败: " + e.getMessage());
        }
    }
}
