package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.business.harness.QuestionMode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别 Agent（三角色之意图识别者）：用 DeepSeek 把用户问题分类成 {@link QuestionMode}，
 * 替代 RuleRouter 里的关键词判型。
 *
 * <p>承诺「永不阻断主链路」：question 空白 / 限流配额不足 / 模型异常或返回无法解析时，
 * 一律安全回退 {@link QuestionMode#GENERAL} 并附 reason，绝不抛异常影响问答主流程
 * （对齐 DoVideo-AI ModeRouter 的 never-block 理念）。
 *
 * <p>独立限流：分类本身是一次 LLM 调用，单独用 Redisson RRateLimiter 配每用户 X/min +
 * 全局 Y/min 两条独立配额，避免挤占答案生成的 model 配额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifier {

    /** 独立限流配额（常量） */
    private static final int USER_LIMIT_PER_MIN = 20;
    private static final int GLOBAL_LIMIT_PER_MIN = 60;
    private static final String KEY_PREFIX = "video-agent:intent:";

    private final ChatModel chatModel;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    /** 分类结果：问题模式 + 简短理由（供日志/审计） */
    public record IntentResult(QuestionMode mode, String reason) {
        static IntentResult general(String reason) {
            return new IntentResult(QuestionMode.GENERAL, reason);
        }
    }

    /**
     * 将用户问题分类为 QUESTION_MODE 之一。
     * 永不抛异常：任何分支失败都回退 GENERAL。
     */
    public IntentResult classify(String question, Long userId) {
        if (question == null || question.isBlank()) {
            return IntentResult.general("question 为空，回退 GENERAL");
        }
        if (!tryUserLimit(userId) || !tryGlobalLimit()) {
            return IntentResult.general("意图分类限流配额不足，回退 GENERAL");
        }
        try {
            String system = """
                    你是视频问答的意图识别 Agent。判断用户问题属于哪种视频问答类型，只输出一个 JSON：
                    {"mode":"SUMMARY|SPOT|VISUAL|ANALYSIS|GENERAL","reason":"简短理由"}
                    - SUMMARY：概括/总结整段视频讲什么、主要内容、主题大意（要求，"讲了什么/总结/概括/大意"类）。
                    - SPOT：事实点查/时间定位（"某处说了什么、何时发生、原话是什么"类）。
                    - VISUAL：画面/视觉/图表/屏幕/截图等画面信息类。
                    - ANALYSIS：对比/原因/影响/关系/评估等分析类。
                    - GENERAL：无法归入以上、或全局泛问。不要分析，不要解释，只输出该 JSON。
                    """;
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(system));
            messages.add(UserMessage.from("问题：" + question));
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
            String text = resp.aiMessage().text();
            return parse(text);
        } catch (Exception e) {
            log.warn("[intent] 分类调用失败，回退 GENERAL: {}", e.getMessage());
            return IntentResult.general("模型调用失败：" + e.getMessage());
        }
    }

    /** 宽松解析模型输出的 JSON，仅当 mode 恰为合法枚举值才采纳，否则回退 GENERAL（绝不让非法值传导到路由）。 */
    private IntentResult parse(String text) {
        if (text == null || !text.contains("{")) {
            return IntentResult.general("模型未返回 JSON，回退 GENERAL");
        }
        String reason = "模型未返回合法 mode";
        try {
            String body = text.substring(text.indexOf("{"), text.lastIndexOf("}") + 1);
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("reason")) {
                reason = root.get("reason").asText();
            }
            String modeStr = root.path("mode").asText("").trim().toUpperCase();
            for (QuestionMode m : QuestionMode.values()) {
                if (m.name().equals(modeStr)) {
                    log.info("[intent] classify mode={} reason={}", m, reason);
                    return new IntentResult(m, reason);
                }
            }
        } catch (Exception e) {
            log.warn("[intent] JSON 解析失败，回退 GENERAL: {}", e.getMessage());
        }
        return IntentResult.general(reason);
    }

    /** 每用户独立配额：X/min。匿名（uid 为 null）时按宽松兜底放行，与项目限流风格一致。 */
    private boolean tryUserLimit(Long userId) {
        if (userId == null) {
            return true;
        }
        return tryAcquire("user:" + userId, USER_LIMIT_PER_MIN);
    }

    /** 全局配额：Y/min。 */
    private boolean tryGlobalLimit() {
        return tryAcquire("global", GLOBAL_LIMIT_PER_MIN);
    }

    private boolean tryAcquire(String bucket, double rate) {
        try {
            RRateLimiter limiter = redisson.getRateLimiter(KEY_PREFIX + bucket);
            limiter.trySetRate(org.redisson.api.RateType.OVERALL,
                    (long) Math.max(1, rate), 1, org.redisson.api.RateIntervalUnit.MINUTES);
            return limiter.tryAcquire(1);
        } catch (Exception e) {
            log.warn("[intent] 限流异常，按放行处理: {}", e.getMessage());
            return true;
        }
    }
}