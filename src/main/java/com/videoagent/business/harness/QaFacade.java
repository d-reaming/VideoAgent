package com.videoagent.business.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.auth.UserContext;
import com.videoagent.access.dto.QaRequest;
import com.videoagent.access.dto.QaResponse;
import com.videoagent.access.dto.QaResponse.ReferenceDto;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.rate.RateLimitService;
import com.videoagent.business.agent.AnswerAgent;
import com.videoagent.business.agent.AnswerReferenceParser;
import com.videoagent.business.agent.AgentRuntime;
import com.videoagent.business.agent.Plan;
import com.videoagent.business.agent.PlannerAgent;
import com.videoagent.business.harness.RuleRouter.RuleResult;
import com.videoagent.business.video.VideoAccessService;
import com.videoagent.middleware.rag.VectorSearchService;
import com.videoagent.storage.entity.ChatSession;
import com.videoagent.storage.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问答门面（harness，亮点 C）：业务封装层。
 * 职责：会话处理 → 规则路由 → 上下文装配 → 通道执行（快/编排）→ 引用结构化 → 后处理落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaFacade {

    private final ChatSessionService chatSessionService;
    private final RuleRouter ruleRouter;
    private final VectorSearchService vectorSearchService;
    private final AnswerAgent answerAgent;
    private final PlannerAgent plannerAgent;
    private final AgentRuntime agentRuntime;
    private final AnswerReferenceParser referenceParser;
    private final RateLimitService rateLimitService;
    private final VideoAccessService videoAccessService;
    private final ObjectMapper objectMapper;

    public QaResponse ask(QaRequest request) {
        // 多租户：问答仅限当前用户拥有的视频（不存在/非属主抛 403/404）
        if (request.videoId() != null) {
            videoAccessService.owned(request.videoId());
        }
        // 问答限流：以当前登录用户为维度（未解析出用户时按匿名放行的宽松兜底）
        Long uid = UserContext.get();
        if (uid != null && !rateLimitService.tryQa(uid)) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
        ChatSession session = chatSessionService.getOrCreate(request.sessionId(), request.videoId());
        RuleResult rule = ruleRouter.route(request, session);
        String history = chatSessionService.historyText(session.getId(), 6);

        List<VectorRepository.SearchHit> hits = List.of();
        String answer;
        if (RuleRouter.CHANNEL_FAST.equals(rule.channel())) {
            // 快通道：确定性检索 + 单轮分级回答
            hits = vectorSearchService.search(request.question(), rule.videoId(),
                    rule.textTopK(), rule.frameTopK());
            var graded = answerAgent.generate(request.question(), buildContext(hits), history);
            answer = graded.answer();
        } else {
            // 语义编排通道：Planner 结构化规划 → Agent Runtime（执行 + Reviewer 回炉）
            Plan plan = plannerAgent.plan(request.question(), rule.questionType(), history);
            answer = agentRuntime.run(request.question(), rule.videoId(),
                    rule.questionType(), history, plan);
        }

        List<ReferenceDto> refs = referenceParser.parse(answer, rule.videoId(), hits);

        // 后处理：消息落库（会话记忆）
        chatSessionService.saveMessage(session.getId(), "user", request.question(), null);
        chatSessionService.saveMessage(session.getId(), "assistant", answer, toJson(refs));

        log.info("[qa] sessionId={} channel={} question={} refs={}",
                session.getId(), rule.channel(), request.question(), refs.size());
        return new QaResponse(session.getId(), rule.videoId(), rule.channel(), answer, refs);
    }

    private String buildContext(List<VectorRepository.SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (VectorRepository.SearchHit hit : hits) {
            sb.append('[').append(fmt(hit.startSec())).append('-').append(fmt(hit.endSec())).append(']')
                    .append("FRAME".equals(hit.type()) ? "（画面）" : "（语音）").append('\n')
                    .append(hit.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private String fmt(Integer sec) {
        if (sec == null) {
            return "00:00";
        }
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private String toJson(List<ReferenceDto> refs) {
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            return "[]";
        }
    }
}
