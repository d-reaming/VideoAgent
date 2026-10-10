package com.videoagent.business.harness;

import com.videoagent.access.dto.QaRequest;
import com.videoagent.business.agent.IntentClassifier;
import com.videoagent.storage.mapper.VideoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 路由已从关键词判型改为 LLM 意图分类（IntentClassifier）。
 * 此处不再断言「关键词→模式」，改为 mock 分类结果，验证路由把 mode 正确落到 StrategyPlan 的
 * mode / backend / 检索参数，以及全局无视频语境下 SUMMARY→GENERAL 的业务回退。
 */
@ExtendWith(MockitoExtension.class)
class RuleRouterTest {

    @Mock VideoMapper videoMapper;
    @Mock IntentClassifier intentClassifier;

    @InjectMocks RuleRouter router;

    private QaRequest q(String question) {
        return new QaRequest(question, null, null);
    }

    @Test
    void analysisMode_routesToAgentBackend() {
        when(intentClassifier.classify(any(), any())).thenReturn(
                new IntentClassifier.IntentResult(QuestionMode.ANALYSIS, "分析类"));
        RuleRouter.StrategyPlan plan = router.route(q("为什么对比X和Y"), null);
        assertEquals(QuestionMode.ANALYSIS, plan.mode());
        assertEquals(Backend.AGENT, plan.backend());
        assertEquals(10, plan.textTopK());
        assertEquals(6, plan.frameTopK());
    }

    @Test
    void visualMode_routesToFastBackend() {
        when(intentClassifier.classify(any(), any())).thenReturn(
                new IntentClassifier.IntentResult(QuestionMode.VISUAL, "画面类"));
        RuleRouter.StrategyPlan plan = router.route(q("画面里显示了什么按钮"), null);
        assertEquals(QuestionMode.VISUAL, plan.mode());
        assertEquals(Backend.FAST, plan.backend());
        assertEquals(6, plan.textTopK());
        assertEquals(8, plan.frameTopK());
    }

    @Test
    void spotMode_routesToFastBackend() {
        when(intentClassifier.classify(any(), any())).thenReturn(
                new IntentClassifier.IntentResult(QuestionMode.SPOT, "时间定位"));
        RuleRouter.StrategyPlan plan = router.route(q("这段在第几秒发生"), null);
        assertEquals(QuestionMode.SPOT, plan.mode());
        assertEquals(Backend.FAST, plan.backend());
        assertEquals(10, plan.textTopK());
        assertEquals(4, plan.frameTopK());
    }

    @Test
    void generalMode_routesToFastBackend() {
        when(intentClassifier.classify(any(), any())).thenReturn(
                new IntentClassifier.IntentResult(QuestionMode.GENERAL, "兜底"));
        RuleRouter.StrategyPlan plan = router.route(q("什么是光栅化"), null);
        assertEquals(QuestionMode.GENERAL, plan.mode());
        assertEquals(Backend.FAST, plan.backend());
        assertEquals(8, plan.textTopK());
    }

    @Test
    void summaryWithoutVideo_fallsBackToGeneral() {
        // 全局（无 videoId）语境下 SUMMARY 应为 GENERAL（FAST 全局归纳）
        when(intentClassifier.classify(any(), any())).thenReturn(
                new IntentClassifier.IntentResult(QuestionMode.SUMMARY, "摘要类"));
        RuleRouter.StrategyPlan plan = router.route(q("讲了什么"), null);
        assertEquals(QuestionMode.GENERAL, plan.mode());
        assertEquals(Backend.FAST, plan.backend());
    }
}