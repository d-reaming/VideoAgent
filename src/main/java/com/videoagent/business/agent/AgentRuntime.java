package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent Runtime（三角色协作执行器）：
 * Planner(Plan 结构化) → 执行 Agent(ToolAgent 工具循环) → Reviewer(验收回炉，最多 MAX_REVISE_ROUNDS 轮)。
 * ABSTRACT/needFullText=true 时预取全量转写注入上下文，避免 topK 片段丢上下文导致"视频讲了什么"答不出。
 *
 * <p>WS2：Reviewer 结构化反馈驱动「定向补证」——出现 unsupportedClaims/requiredTimestamps 时先定向
 * 重新检索注入目标证据，再重写；无证据缺口仅表达问题时沿用当前上下文重写，避免无意义整篇重写。
 *
 * <p>预算硬上限：单次问答用一个 {@link AgentBudget}（LLM 调用数 + 累计 token + 墙钟超时），
 * 工具循环与 Reviewer 回炉共用同一预算；任意超限即中断返回当前最佳，杜绝无界消费模型配额。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntime {

    private static final int MAX_TOOL_ROUNDS = 5;
    private static final int MAX_REVISE_ROUNDS = 2;

    private final ChatModel chatModel;
    private final ToolAgent toolAgent;
    private final ReviewerAgent reviewerAgent;
    private final ObjectMapper objectMapper;

    /** 预算配置（video-agent.agent.budget.*），每次问答 new 一个实例 */
    @Value("${video-agent.agent.budget.max-llm-calls:12}")
    private int budgetMaxLlmCalls;
    @Value("${video-agent.agent.budget.max-tokens:20000}")
    private int budgetMaxTokens;
    @Value("${video-agent.agent.budget.deadline-ms:120000}")
    private long budgetDeadlineMs;

    private AgentBudget newBudget() {
        return new AgentBudget(budgetMaxLlmCalls, budgetMaxTokens, budgetDeadlineMs);
    }

    public String run(String question, Long videoId, String questionType, String historyText, Plan plan, String followUpHint) {
        String system = """
                你是视频问答的执行与检索 Agent。流程：
                1. 分析问题；需要更多信息时调用工具：
                   - ragSearch(question, videoId, textTopK, frameTopK, ocrTopK)：语义检索语音、画面与字幕文字内容，返回 JSON
                   - videoMeta(videoId)：查询视频元数据（标题/时长/分段数）
                   - timeline(videoId, limit)：查看转写时间轴概览
                   - fullText(videoId)：拉取视频全量转写（纯语音，用于时间轴/逐段梳理）
                   - globalContext(videoId)：拉取视频全量上下文（语音转写+画面描述+字幕文字+元数据，用于总结/大意/全局型问题）
                2. 依据工具结果组织最终回答（中文，自然、直接、可读），满足引用要求：
                   - 引用语音内容标注 [mm:ss]（如 [01:23]）；引用画面信息标注 [mm:ss]（画面）；引用画面文字/字幕标注 [mm:ss]（字幕）
                   - 依据工具/预取上下文作答，不要编造
                   - 全局总结/大意类问题：主动归纳全文，给出真实内容总结。
                   - 有直接证据就正常断言；无直接证据但可合理推断时，用自然语言给出结论，仅在确实需要边界时轻量标注"（推测）"，
                     避免通篇"我只能确认/推断/依据未覆盖"式免责。
                   - 若收到「上一问回指提示」，则本问题省略主语，围绕上一问作答，不要重新概括全片。
                3. 回答语言：一律使用简体中文；视频中的外语专名（人名/作品名/机构/术语等）翻译为中文，必要时括号附原文
                   （如 安（アン））；不要原样堆砌片假名/外文术语。
                4. 规划要点见下方「检索规划」。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        if (historyText != null && !historyText.isBlank()) {
            messages.add(SystemMessage.from("对话历史：\n" + historyText));
        }
        if (followUpHint != null && !followUpHint.isBlank()) {
            messages.add(SystemMessage.from(followUpHint));
        }
        // 注入检索规划
        messages.add(SystemMessage.from("检索规划：\n" + plan.describe()));
        // ABSTRACT/全局型问题：预取全量上下文（语音+画面+字幕+元数据），保证摘要有完整依据
        if (plan.needFullText()) {
            String global = toolAgent.execute("globalContext", "{\"videoId\":" + videoId + "}");
            messages.add(SystemMessage.from(
                    "已提供视频全量上下文（语音转写+画面描述+字幕文字+元数据，依此作答，无需再检索全局）：\n" + global));
        }
        messages.add(UserMessage.from("问题类型：" + questionType + "\n目标视频ID：" + videoId + "\n问题：" + question));

        List<ToolSpecification> specs = buildSpecs();

        // ① 执行 Agent：工具循环生成首次回答（共用预算：LLM 调用/token/墙钟超时）
        AgentBudget budget = newBudget();
        String answer = runToolLoop(messages, specs, budget);
        if (answer == null) {
            answer = "（Agent 未能生成回答）";
        }

        // ② Reviewer：保真验收（注入问答事实依据供逐项核验），未通过则定向补证后回炉；回炉共用同一预算，预算耗尽则接受当前最佳
        if (videoId != null) {
            String groundTruth = toolAgent.execute("globalContext", "{\"videoId\":" + videoId + "}");
            for (int round = 1; round <= MAX_REVISE_ROUNDS; round++) {
                if (budget.isExceeded()) {
                    log.warn("[runtime] 预算已超限，中断回炉，接受当前回答预算用={} token={} 耗时={}ms, calls={}",
                            budget.maxLlmCalls(), budget.tokens(), budget.elapsedMs(), budget.llmCalls());
                    break;
                }
                budget.consumeLlmCall();
                ReviewerAgent.ReviewResult review = reviewerAgent.review(question, answer, videoId, groundTruth);
                if (review.pass() || budget.isExceeded()) {
                    if (budget.isExceeded()) {
                        log.warn("[runtime] 回炉后预算超限，接受当前回答 calls={} token={}", budget.llmCalls(), budget.tokens());
                    }
                    break;
                }
                log.info("[runtime] 第{}次回炉, {}", round, review.reason());
                // 定向补证：有证据缺口时先把目标证据注入上下文，再重写（确保上下文变化而非重复生成）
                List<String> targeted = new ArrayList<>(review.unsupportedClaims());
                targeted.addAll(review.requiredTimestamps());
                if (!targeted.isEmpty()) {
                    StringBuilder evidence = new StringBuilder();
                    for (String q : targeted) {
                        evidence.append("对「").append(q).append("」定向检索结果：\n");
                        evidence.append(toolAgent.execute("ragSearch", ragSearchArgs(q, videoId))).append('\n');
                    }
                    messages.add(SystemMessage.from("补充证据（用于核实上述反馈，依此修正或删除失实的引用）：\n" + evidence));
                }
                StringBuilder fb = new StringBuilder("验收未通过：" + review.reason());
                if (!review.missingRequirements().isEmpty()) {
                    fb.append("\n请补全以下遗漏要求：").append(review.missingRequirements());
                }
                if (!review.feedback().isEmpty()) {
                    fb.append("\n调整建议：").append(review.feedback());
                }
                fb.append("\n请输出修正后的最终回答；若某条 [mm:ss] 引用失实，用上方补充证据核实后改正或删除。");
                messages.add(SystemMessage.from(fb.toString()));
                messages.add(UserMessage.from("请输出修正后的最终回答。"));
                if (budget.isExceeded()) {
                    log.warn("[runtime] 重写前预算已超限，接受原回答");
                    break;
                }
                budget.consumeLlmCall();
                ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
                budget.consumeTokens(tokensOf(resp));
                AiMessage ai = resp.aiMessage();
                if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
                    for (var ter : ai.toolExecutionRequests()) {
                        String result = toolAgent.execute(ter.name(), ter.arguments());
                        messages.add(ToolExecutionResultMessage.from(ter, result));
                    }
                }
                answer = ai.text() == null ? answer : ai.text();
            }
        }
        log.info("[runtime] 问答完成 budget: calls={}/{} token={}/{} 耗时={}ms", budget.llmCalls(),
                budget.maxLlmCalls(), budget.tokens(), budget.maxTokens(), budget.elapsedMs());
        return answer == null ? "（Agent 未生成最终回答）" : answer;
    }

    /**
     * WS1 断言修正：回答已产出、引用经确定性校验后存在失实条目时，做一次定向修正，
     * 让模型改正或删除伪引用（仅 AGENT 通道且存在失实引用时由 QaFacade 调用）。
     */
    public String refineCitations(String question, String originalAnswer, Long videoId,
                                  String historyText, Plan plan, String followUpHint, List<String> unverifiedNotes) {
        if (unverifiedNotes == null || unverifiedNotes.isEmpty()) {
            return null;
        }
        String system = """
                你是视频问答的引用修正 Agent。用户给你一条已生成的回答，以及其中被校验为「失实/无法核对」的若干引用说明。
                请逐条处理：若该引用能凭视频内容找到对应正确时间戳，就改正；确实失实的就删除或改述。不得新增未提供的引用。
                只输出修正后的完整回答（简体中文），不要解释过程。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        if (historyText != null && !historyText.isBlank()) {
            messages.add(SystemMessage.from("对话历史：\n" + historyText));
        }
        if (followUpHint != null && !followUpHint.isBlank()) {
            messages.add(SystemMessage.from(followUpHint));
        }
        messages.add(SystemMessage.from("检索规划：\n" + plan.describe()));
        messages.add(UserMessage.from("问题：" + question
                + "\n原回答：\n" + originalAnswer
                + "\n\n以下引用被校验为失实/无法核对，请改正或删除：\n" + String.join("\n", unverifiedNotes)));
        messages.add(SystemMessage.from("如需修正某个时间戳，可先调用 ragSearch 检索核实正确时间再改。"));
        List<ToolSpecification> specs = buildSpecs();
        String corrected = runToolLoop(messages, specs, newBudget());
        return corrected == null || corrected.isBlank() ? null : corrected;
    }

    /** 工具循环：每轮在预算内调用 LLM，累计 token，超限即中断返回当前最佳答案 */
    private String runToolLoop(List<ChatMessage> messages, List<ToolSpecification> specs, AgentBudget budget) {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (budget.isExceeded()) {
                log.warn("[runtime] 预算已超限，中断工具循环 calls={}/{} token={}/{} 耗时={}ms",
                        budget.llmCalls(), budget.maxLlmCalls(), budget.tokens(), budget.maxTokens(), budget.elapsedMs());
                break;
            }
            budget.consumeLlmCall();
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build());
            budget.consumeTokens(tokensOf(resp));
            AiMessage ai = resp.aiMessage();
            messages.add(ai);
            if (ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
                return ai.text();
            }
            log.info("[runtime] 第{}轮调用 {} 个工具", round + 1, ai.toolExecutionRequests().size());
            for (var ter : ai.toolExecutionRequests()) {
                String result = toolAgent.execute(ter.name(), ter.arguments());
                messages.add(ToolExecutionResultMessage.from(ter, result));
            }
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof AiMessage finalAi ? finalAi.text() : null;
    }

    /** 从 LLM 响应取累计 token(input+output)；无 token 统计（null/异常）按 0 处理，仍受轮次/墙钟约束 */
    private int tokensOf(ChatResponse resp) {
        try {
            var tu = resp.tokenUsage();
            if (tu == null) {
                return 0;
            }
            Integer total = tu.totalTokenCount();
            if (total != null) {
                return total;
            }
            Integer in = tu.inputTokenCount();
            Integer out = tu.outputTokenCount();
            return (in == null ? 0 : in) + (out == null ? 0 : out);
        } catch (Exception e) {
            return 0;
        }
    }

    private List<ToolSpecification> buildSpecs() {
        return List.of(
                ToolSpecification.builder()
                        .name("ragSearch")
                        .description("语义检索视频内容（语音文本+画面描述+字幕文字），返回命中片段 JSON 列表")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("question")
                                .addStringProperty("videoId")
                                .addIntegerProperty("textTopK")
                                .addIntegerProperty("frameTopK")
                                .addIntegerProperty("ocrTopK")
                                .required("question")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("videoMeta")
                        .description("查询视频元数据（标题/时长/分段数/状态）")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("videoId")
                                .required("videoId")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("timeline")
                        .description("查询视频转写时间轴概览（前 N 条分段）")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("videoId")
                                .addIntegerProperty("limit")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("fullText")
                        .description("查询视频全量转写文本（含时间戳，仅语音），用于逐段/时间轴类问题")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("videoId")
                                .required("videoId")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("globalContext")
                        .description("查询视频全量上下文（语音转写+画面描述+字幕文字+元数据），用于总结/大意/内容概述等全局型问题")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("videoId")
                                .required("videoId")
                                .build())
                        .build());
    }

    /** 构造 ragSearch 的 JSON 参数（用 ObjectMapper 保证字符串正确转义） */
    private String ragSearchArgs(String question, Long videoId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "question", question, "videoId", videoId,
                    "textTopK", 5, "frameTopK", 3, "ocrTopK", 3));
        } catch (Exception e) {
            return "{\"question\":\"" + question.replace("\"", "\\\"") + "\",\"videoId\":" + videoId
                    + ",\"textTopK\":5,\"frameTopK\":3,\"ocrTopK\":3}";
        }
    }
}