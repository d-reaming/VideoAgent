package com.videoagent.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.business.harness.QuestionMode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 无法离线真调 LLM，全部用 mock：核心断言 classify 在「不可用/解析失败/限流」时
 * 永不抛异常、安全回退 GENERAL，以及正常解析时落到对应 QuestionMode。
 */
@ExtendWith(MockitoExtension.class)
class IntentClassifierTest {

    @Mock ChatModel chatModel;
    @Mock RedissonClient redisson;
    @Mock RRateLimiter limiter;
    @Mock ChatResponse response;
    @Mock AiMessage aiMessage;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IntentClassifier classifier() {
        return new IntentClassifier(chatModel, redisson, objectMapper);
    }

    private void allowRate() {
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);
    }

    @Test
    void blankQuestion_returnsGeneral() {
        // 空白问题在限流/LLM 之前即回退，无需打桩
        IntentClassifier.IntentResult r = classifier().classify("   ", 1L);
        assertEquals(QuestionMode.GENERAL, r.mode());
    }

    @Test
    void modelThrows_returnsGeneral_neverBlocks() {
        allowRate();
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("llm down"));
        IntentClassifier.IntentResult r = classifier().classify("讲了什么", 1L);
        assertEquals(QuestionMode.GENERAL, r.mode());
    }

    @Test
    void rateLimited_returnsGeneral() {
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(false);
        IntentClassifier.IntentResult r = classifier().classify("讲了什么", 1L);
        assertEquals(QuestionMode.GENERAL, r.mode());
    }

    @Test
    void invalidJson_returnsGeneral() {
        allowRate();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn("我不懂你在问什么");
        IntentClassifier.IntentResult r = classifier().classify("讲了什么", 1L);
        assertEquals(QuestionMode.GENERAL, r.mode());
    }

    @Test
    void validAnalysis_returnsAnalysis() {
        allowRate();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn("{\"mode\":\"ANALYSIS\",\"reason\":\"对比类\"}");
        IntentClassifier.IntentResult r = classifier().classify("为什么对比X和Y", 1L);
        assertEquals(QuestionMode.ANALYSIS, r.mode());
        assertEquals("对比类", r.reason());
    }

    @Test
    void unknownModeValue_fallsBackToGeneral() {
        allowRate();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn("{\"mode\":\"HACK\",\"reason\":\"无法识别\"}");
        IntentClassifier.IntentResult r = classifier().classify("随便问问", 1L);
        assertEquals(QuestionMode.GENERAL, r.mode());
    }
}