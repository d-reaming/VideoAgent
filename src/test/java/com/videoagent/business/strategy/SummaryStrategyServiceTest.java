package com.videoagent.business.strategy;

import com.videoagent.business.agent.AnswerAgent;
import com.videoagent.business.agent.GlobalContextTool;
import com.videoagent.business.agent.GradedAnswer;
import com.videoagent.business.evidence.Confidence;
import com.videoagent.business.evidence.Evidence;
import com.videoagent.business.evidence.EvidenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryStrategyServiceTest {

    @Mock GlobalContextTool globalContextTool;
    @Mock AnswerAgent answerAgent;

    @InjectMocks SummaryStrategyService service;

    @Test
    void chunk_byLen() {
        assertEquals(List.of("012", "345", "6"), SummaryStrategyService.chunk("0123456", 3));
        assertEquals(List.of("abcd", "efgh"), SummaryStrategyService.chunk("abcdefgh", 4));
        assertTrue(SummaryStrategyService.chunk("", 3).isEmpty());
        assertTrue(SummaryStrategyService.chunk(null, 3).isEmpty());
    }

    @Test
    void summarize_singleChunk_returnsDirectDigest() {
        String text = "短内容".repeat(50);
        when(globalContextTool.build(1L)).thenReturn(List.of(
                new Evidence(EvidenceType.AUDIO, 0, 10, text, Confidence.DIRECT)));
        when(answerAgent.generate(anyString(), anyString(), any(), any()))
                .thenReturn(GradedAnswer.plain("单块摘要"));

        String result = service.summarize(1L, "讲了什么", null, null);

        assertEquals("单块摘要", result);
        // map 1 + reduce 0（块数==1）
        verify(answerAgent, times(1)).generate(anyString(), anyString(), any(), any());
    }

    @Test
    void summarize_multiChunk_mergesDigests() {
        String text = "x".repeat(10000); // > 2 * CHUNK_LEN → 3 块
        when(globalContextTool.build(2L)).thenReturn(List.of(
                new Evidence(EvidenceType.AUDIO, 0, 10, text, Confidence.DIRECT)));
        when(answerAgent.generate(anyString(), anyString(), any(), any()))
                .thenReturn(GradedAnswer.plain("要点"));

        String result = service.summarize(2L, "讲了什么", null, null);

        assertEquals("要点", result);
        // map 3 + reduce 1
        verify(answerAgent, times(4)).generate(anyString(), anyString(), any(), any());
    }
}