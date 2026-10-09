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

/**
 * 验收 Agent（三角色之 Reviewer，S3 保真监督）：
 * 基于「问答事实依据」对回答逐项核验三项，输出 {pass, reason}，供执行 Agent 修正后重答（最多 MAX_REVISE_ROUNDS 轮）。
 * 三项核验：
 * ① 置信越权：仅由推断得出的结论被当作既定事实（无直接依据却斩钉截铁）→ 要求标注"（推断）"或降级表述；
 * ② 漏答：问题可据常识+已有依据合理推断，却以"未找到/无法确认"拒答 → 要求补上带"（推断）"的推断；
 * ③ 幻觉：回答了事实依据中完全没有的内容（含凭空捏造时间戳）→ 要求删除或改为"依据未覆盖"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent {

    private final ChatModel chatModel;

    /** 注入审核的事实依据最大长度，防止超长视频撑爆上下文 */
    private static final int GROUND_TRUTH_CAP = 20000;

    public record ReviewResult(boolean pass, String reason) {
    }

    public ReviewResult review(String question, String answer, Long videoId, String groundTruth) {
        // 全局问题（无视频）或缺少依据时，仅做基本充分性校验
        if (videoId == null || groundTruth == null || groundTruth.isBlank()) {
            return basicCheck(question, answer);
        }
        String system = """
                你是视频问答的验收兼保真监督 Agent。基于给定的「问答事实依据」逐项核验回答，输出 JSON {"pass": true/false, "reason": "说明"}。
                三项核验（只要命中其一即判不过）：
                1. 置信越权：回答把"仅凭推断"得出的结论当成既成事实陈述（依据中没有直接支撑就斩钉截铁）→ reason 说明应标注"（推断）"或降级表述。
                2. 漏答：问题可依据视频内容+常识做合理推断，但回答以"未找到/无法确认"简单拒绝而没有给出 → reason 说明应补上带"（推断）"的推断。
                3. 幻觉：回答出现了事实依据中完全没有的内容，或引用了依据中不存在的 [mm:ss] → reason 说明应删除虚构成分或改为"依据未覆盖该信息"。
                若三项均不命中（含义充分、置信恰当、无虚构）则 pass=true，reason 留空。
                只输出 JSON，不要输出其他内容。
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
            return new ReviewResult(true, "校验服务异常，按通过处理");
        }
    }

    /** 无视频/无依据时：仅校验是否充分作答、是否说明"未找到"而非编造 */
    private ReviewResult basicCheck(String question, String answer) {
        String system = """
                你是视频问答的验收 Agent。判断回答是否合格，输出 JSON {"pass": true/false, "reason": "说明"}。
                判定标准：
                1. 是否充分回答用户问题（避免自相矛盾、答非所问、明显信息缺失）。
                2. 是否明确说明"视频中未找到相关讲解"当检索不到内容时，而非编造。
                只输出 JSON，不要输出其他内容。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        messages.add(UserMessage.from("问题：" + question + "\n回答：\n" + answer));
        try {
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            return parse(resp.aiMessage().text());
        } catch (Exception e) {
            return new ReviewResult(true, "校验服务异常，按通过处理");
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