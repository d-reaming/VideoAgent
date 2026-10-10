package com.videoagent.business.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 验收 Agent（三角色之 Reviewer，S3 保真监督）：
 * 基于「问答事实依据」逐项核验回答，输出结构化反馈 {pass, missingRequirements[], unsupportedClaims[],
 * requiredTimestamps[], feedback[]}，供执行 Agent 定向补证而非整篇盲目重写。
 * 结构化字段语义（对齐 DoVideoAI Critic）：
 * ① missingRequirements —— 用户要求被遗漏的作答点 → 触发补全；
 * ② unsupportedClaims —— 无足够证据的结论/伪引用 → 触发定向重新检索后修正或删除；
 * ③ requiredTimestamps —— 需要直接加载核实的时间片 → 触发定向重检；
 * ④ feedback —— 已有证据、仅表达/结构问题 → 沿用当前上下文重写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent {

    private final ChatModel chatModel;

    /** 注入审核的事实依据最大长度，防止超长视频撑爆上下文 */
    private static final int GROUND_TRUTH_CAP = 20000;

    /** 结构化验收结果 */
    public record ReviewResult(boolean pass, List<String> missingRequirements, List<String> unsupportedClaims,
                               List<String> requiredTimestamps, List<String> feedback) {

        /** 可读摘要（日志用） */
        public String reason() {
            if (pass) {
                return "通过";
            }
            StringBuilder sb = new StringBuilder();
            appendIf(sb, "遗漏", missingRequirements);
            appendIf(sb, "无证据结论", unsupportedClaims);
            appendIf(sb, "需核实时间", requiredTimestamps);
            appendIf(sb, "反馈", feedback);
            return sb.length() == 0 ? "不通过" : sb.toString();
        }

        private static void appendIf(StringBuilder sb, String label, List<String> items) {
            if (items != null && !items.isEmpty()) {
                sb.append(label).append('=').append(items);
            }
        }
    }

    public ReviewResult review(String question, String answer, Long videoId, String groundTruth) {
        // 全局问题（无视频）或缺少依据时，仅做基本充分性校验
        if (videoId == null || groundTruth == null || groundTruth.isBlank()) {
            return basicCheck(question, answer);
        }
        String system = """
                你是视频问答的验收兼保真监督 Agent。基于给定的「问答事实依据」逐项核验回答，输出严格 JSON：
                {"pass": true/false, "missingRequirements": [], "unsupportedClaims": [], "requiredTimestamps": [], "feedback": []}
                判定（命中任一即 pass=false）：
                1. missingRequirements：用户问的要件被遗漏或未覆盖。
                2. unsupportedClaims：结论在依据中找不到直接支撑，或引用了依据/真实内容里不存在的 [mm:ss]（包含编造时间戳）。
                3. requiredTimestamps：需要到某时间片（给出 [mm:ss]）核实但当前证据不足。
                4. feedback：证据尚可，仅需调整表达、补充推断标注时，放入一条普通修改建议。
                pass=true 时四数组留空。只输出 JSON，不要输出其他内容。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        messages.add(UserMessage.from("问答事实依据：\n" + cap(groundTruth)
                + "\n\n问题：" + question + "\n\n回答：\n" + answer + "\n\n视频ID：" + videoId));
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            String text = resp.aiMessage().text();
            return parse(text);
        } catch (Exception e) {
            log.warn("[reviewer] 校验失败，默认通过: {}", e.getMessage());
            return pass();
        }
    }

    /** 无视频/无依据时：仅校验是否充分作答、是否说明"未找到"而非编造 */
    private ReviewResult basicCheck(String question, String answer) {
        String system = """
                你是视频问答的验收 Agent。判断回答是否合格，输出严格 JSON：
                {"pass": true/false, "missingRequirements": [], "unsupportedClaims": [], "requiredTimestamps": [], "feedback": []}
                判定标准：
                1. 是否充分回答用户问题（避免自相矛盾、答非所问、明显信息缺失）——缺失则填入 missingRequirements。
                2. 是否编造视频中不存在的内容——编造则填入 unsupportedClaims。
                只输出 JSON，不要输出其他内容。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        messages.add(UserMessage.from("问题：" + question + "\n回答：\n" + answer));
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            return parse(resp.aiMessage().text());
        } catch (Exception e) {
            return pass();
        }
    }

    private String cap(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= GROUND_TRUTH_CAP) {
            return s;
        }
        return s.substring(0, GROUND_TRUTH_CAP) + "\n……（超长依据已截断）";
    }

    private ReviewResult parse(String text) {
        if (text == null || !text.contains("{")) {
            return pass();
        }
        String json = text.substring(text.indexOf("{"), text.lastIndexOf("}") + 1);
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            boolean pass = root.path("pass").asBoolean(true);
            return new ReviewResult(pass,
                    strList(root, "missingRequirements"),
                    strList(root, "unsupportedClaims"),
                    strList(root, "requiredTimestamps"),
                    strList(root, "feedback"));
        } catch (JsonProcessingException e) {
            log.warn("[reviewer] 结果解析失败: {}", e.getMessage());
            return pass();
        }
    }

    private List<String> strList(JsonNode root, String field) {
        List<String> out = new ArrayList<>();
        JsonNode node = root.path(field);
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** 默认通过（校验异常/解析失败时的安全降级） */
    private ReviewResult pass() {
        return new ReviewResult(true, List.of(), List.of(), List.of(), List.of());
    }
}