package com.videoagent.business.agent;

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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Runtime（三角色协作执行器）：
 * Planner(Plan 结构化) → 执行 Agent(ToolAgent 工具循环) → Reviewer(验收回炉，最多 MAX_REVISE_ROUNDS 轮)。
 * ABSTRACT/needFullText=true 时预取全量转写注入上下文，避免 topK 片段丢上下文导致"视频讲了什么"答不出。
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

    public String run(String question, Long videoId, String questionType, String historyText, Plan plan) {
        String system = """
                你是视频问答的执行与检索 Agent。流程：
                1. 分析问题；需要更多信息时调用工具：
                   - ragSearch(question, videoId, textTopK, frameTopK)：语义检索语音与画面内容，返回 JSON
                   - videoMeta(videoId)：查询视频元数据（标题/时长/分段数）
                   - timeline(videoId, limit)：查看转写时间轴概览
                   - fullText(videoId)：拉取视频全量转写（纯语音，用于时间轴/逐段梳理）
                   - globalContext(videoId)：拉取视频全量上下文（语音转写+画面描述+元数据，用于总结/大意/全局型问题）
                2. 依据工具结果组织最终回答（中文），并满足引用要求：
                   - 引用语音内容标注 [mm:ss]（如 [01:23]）
                   - 引用画面信息标注 [mm:ss]（画面）
                   - 依据工具/预取上下文作答，不要编造
                   - 全局总结/大意类问题：主动归纳全文，给出真实内容总结，不要反复"我只能确认"式免责。
                   - 无直接证据但可据已有证据+常识合理推断时，给出该推断并标注"（推断）"；既无直接也无从推断才说"视频未覆盖"。
                   - 若用户是在追问/要求进一步分析（对话历史里你已对同一视频给出整体总结）：直接基于已有总结展开，不要重复从头归纳。
                3. 规划要点见下方「检索规划」。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(system));
        if (historyText != null && !historyText.isBlank()) {
            messages.add(SystemMessage.from("对话历史：\n" + historyText));
        }
        // 注入检索规划
        messages.add(SystemMessage.from("检索规划：\n" + plan.describe()));
        // ABSTRACT/全局型问题：预取全量上下文（语音+画面+元数据），保证摘要有完整依据
        if (plan.needFullText()) {
            String global = toolAgent.execute("globalContext", "{\"videoId\":" + videoId + "}");
            messages.add(SystemMessage.from(
                    "已提供视频全量上下文（语音转写+画面描述+元数据，依此作答，无需再检索全局）：\n" + global));
        }
        messages.add(UserMessage.from("问题类型：" + questionType + "\n目标视频ID：" + videoId + "\n问题：" + question));

        List<ToolSpecification> specs = buildSpecs();

        // ① 执行 Agent：工具循环生成首次回答
        String answer = runToolLoop(messages, specs);
        if (answer == null) {
            answer = "（Agent 未能生成回答）";
        }

        // ② Reviewer：保真验收（注入问答事实依据供逐项核验），未通过则携带意见回炉
        if (videoId != null) {
            String groundTruth = toolAgent.execute("globalContext", "{\"videoId\":" + videoId + "}");
            for (int round = 1; round <= MAX_REVISE_ROUNDS; round++) {
                ReviewerAgent.ReviewResult review = reviewerAgent.review(question, answer, videoId, groundTruth);
                if (review.pass()) {
                    break;
                }
                log.info("[runtime] 第{}次回炉, reason={}", round, review.reason());
                messages.add(SystemMessage.from("验收未通过，意见：" + review.reason()
                        + "\n请据此修正回答。若某个引用时间不对，可重新检索核实后改正。"));
                messages.add(UserMessage.from("请输出修正后的最终回答。"));
                ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(messages).build());
                AiMessage ai = resp.aiMessage();
                if (ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) {
                    // 回炉时若再次请求工具，先执行一次再生成
                    for (var ter : ai.toolExecutionRequests()) {
                        String result = toolAgent.execute(ter.name(), ter.arguments());
                        messages.add(ToolExecutionResultMessage.from(ter, result));
                    }
                    continue;
                }
                answer = ai.text() == null ? answer : ai.text();
            }
        }
        return answer == null ? "（Agent 未生成最终回答）" : answer;
    }

    /** 工具循环：最多 MAX_TOOL_ROUNDS 轮，直到模型输出纯文本回答 */
    private String runToolLoop(List<ChatMessage> messages, List<ToolSpecification> specs) {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build());
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

    private List<ToolSpecification> buildSpecs() {
        return List.of(
                ToolSpecification.builder()
                        .name("ragSearch")
                        .description("语义检索视频内容（语音文本+画面描述），返回命中片段 JSON 列表")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("question")
                                .addStringProperty("videoId")
                                .addIntegerProperty("textTopK")
                                .addIntegerProperty("frameTopK")
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
                        .description("查询视频全量上下文（语音转写+画面描述+元数据），用于总结/大意/内容概述等全局型问题")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("videoId")
                                .required("videoId")
                                .build())
                        .build());
    }
}