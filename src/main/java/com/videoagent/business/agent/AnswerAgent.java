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
 * 回答 Agent（快通道单轮生成，S2 引入分级断言）：
 * 基于检索上下文生成带 [mm:ss] 引用的最终回答，并按置信分级输出结构化断言。
 * 分级三态：DIRECT（有直接证据）直接断言 / INFERENCE（无直接证据但可合理推断）标注"（推断）"给出 /
 * UNSURE（无从判定）明确"视频未覆盖该信息"，不再一刀切拒绝。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerAgent {

    private final ChatModel chatModel;
    private final GradedAnswerParser parser;

    public GradedAnswer generate(String question, String context, String historyText, String followUpHint) {
        String system = """
                你是视频内容问答助手。基于给定的「视频检索上下文」回答用户问题，并输出结构化分级结果。
                1. 引用语音内容标注 [mm:ss]（如 [01:23]）；引用画面信息标注 [mm:ss]（画面）；引用画面文字/字幕标注 [mm:ss]（字幕）。
                2. 区分两类问题：
                   - 全局总结/大意类：主动归纳上下文，给出真正的内容总结，不要反复声明"我只能确认"。
                   - 事实点查类：直接回答检索到的内容。
                3. 置信分级作答（不要过度保守）：
                   - 有直接证据（转写原文/画面字幕/画面描述/元数据）：直接断言，正常陈述，不要反复加"（推断）"。
                   - 无直接证据但可合理推断：用自然语言给出结论，仅在确实需要边界时轻量标注"（推测）"。
                   - 两者皆无：才说"视频未覆盖该信息"。
                   始终用自然、直接、可读的中文回答，避免通篇堆砌"推断/依据未覆盖"式免责。
                4. 回答语言：一律使用简体中文；视频中的外语专名（人名/作品名/机构/术语等）翻译为中文，必要时括号附原文
                   （如 安（アン））；不要原样堆砌片假名/外文术语。
                5. 若收到「上一问回指提示」，则本问题省略主语，围绕上一问作答，不要重新概括全片。
                6. 只输出 JSON，格式：
                {"answer":"给用户的可读中文回答（含引用标注）","assertions":[
                   {"text":"可独立核验的陈述","confidence":"DIRECT|INFERENCE|UNSURE",
                    "evidence":[{"type":"AUDIO|VISION|OCR|RETRIEVED|META","startSec":20,
                    "endSec":35,"text":"支撑文本"}]}]}
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        if (historyText != null && !historyText.isBlank()) {
            messages.add(SystemMessage.from("对话历史：\n" + historyText));
        }
        if (followUpHint != null && !followUpHint.isBlank()) {
            messages.add(SystemMessage.from(followUpHint));
        }
        messages.add(UserMessage.from("检索上下文：\n" + context + "\n\n问题：" + question));
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            String raw = resp.aiMessage().text();
            GradedAnswer graded = parser.parse(raw);
            if (graded != null) {
                return graded;
            }
            // 兜底：模型未走结构化输出，按纯文本返回
            return GradedAnswer.plain(raw);
        } catch (Exception e) {
            throw new com.videoagent.access.exception.BusinessException("回答生成失败: " + e.getMessage());
        }
    }
}