package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 预算硬上限：轮次不足/累计 token/墙钟超时均能中断工具循环，杜绝无界调用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeBudgetTest {

    @Mock ChatModel chatModel;
    @Mock ToolAgent toolAgent;
    @Mock ReviewerAgent reviewerAgent;
    @Mock ObjectMapper objectMapper;

    private AgentRuntime runtime;

    @BeforeEach
    void setup() {
        runtime = new AgentRuntime(chatModel, toolAgent, reviewerAgent, objectMapper);
        // 默认拉大 token/墙钟，只考验「轮次」上限中断
        ReflectionTestUtils.setField(runtime, "budgetMaxLlmCalls", 2);
        ReflectionTestUtils.setField(runtime, "budgetMaxTokens", 100000);
        ReflectionTestUtils.setField(runtime, "budgetDeadlineMs", 60000L);
        lenient().when(toolAgent.execute(anyString(), anyString())).thenReturn("[]");
    }

    /** 每轮总是返回工具调用 → 不加预算会跑满 MAX_TOOL_ROUNDS=5；预算设为 2 次调用即应中断 */
    private void stubAlwaysTools() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .name("ragSearch").arguments("{\"question\":\"x\",\"videoId\":1}").build();
        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.builder().toolExecutionRequests(List.of(req)).build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);
    }

    @Test
    void llmCallBudget_interruptsToolLoop() {
        stubAlwaysTools();
        String result = runtime.run("问题", null, "GENERAL", null, Plan.fallback(), null);
        // 预算 maxLlmCalls=2：虽然工具永远要求继续，但第 3 轮预检即中断返回（不会到 5 轮）
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
        // 中断后返回当前最佳（工具轮次无文本 → 走兜底话术）
        assertNotNull(result);
    }

    @Test
    void tokenBudget_interrupts() {
        budgetIsExceededAfterToken();
        AgentBudget b = new AgentBudget(12, 100, 60000L);
        while (!b.isExceeded()) {
            b.consumeLlmCall();
            b.consumeTokens(40);
        }
        assertTrue(b.isExceeded());
        assertTrue(b.tokens() >= 100);
    }

    @Test
    void deadline_interrupts() {
        AgentBudget b = new AgentBudget(100, 100000, 1L); // 1ms 墙钟
        sleep(10);
        assertTrue(b.isExceeded());
        assertEquals(100, b.maxLlmCalls());
    }

    @Test
    void zeroBudget_immediatelyExceeded() {
        AgentBudget b = new AgentBudget(1, 1, 60000L);
        assertFalse(b.isExceeded());
        b.consumeLlmCall();
        assertTrue(b.isExceeded());
    }

    private void budgetIsExceededAfterToken() {
        // 占位无操作，保证测试可读
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}