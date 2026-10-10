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
import com.videoagent.business.evidence.ReferenceVerifier;
import com.videoagent.business.evidence.ReferenceVerifier.Verification;
import com.videoagent.business.evidence.ReferenceVerifier.VerificationSource;
import com.videoagent.business.harness.RuleRouter.StrategyPlan;
import com.videoagent.business.qa.QaCheckpointService;
import com.videoagent.business.strategy.SummaryStrategyService;
import com.videoagent.business.video.VideoAccessService;
import com.videoagent.middleware.rag.VectorSearchService;
import com.videoagent.storage.entity.ChatSession;
import com.videoagent.storage.entity.QaCheckpoint;
import com.videoagent.storage.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final ReferenceVerifier referenceVerifier;
    private final QaCheckpointService qaCheckpointService;
    private final RateLimitService rateLimitService;
    private final VideoAccessService videoAccessService;
    private final SummaryStrategyService summaryStrategyService;
    private final ObjectMapper objectMapper;

    /** 追问触发器：明确的省略主语回指词，命中且为短问，视为追问上一论 */
    private static final List<String> FOLLOWUP_WORDS = List.of("猜测", "猜猜", "能猜", "你觉得", "你认为", "认同",
            "再具体", "再详细", "展开讲讲", "展开", "举个例子", "换句话说", "也就是说", "怎么理解");

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
        StrategyPlan plan = ruleRouter.route(request, session);
        String history = chatSessionService.historyText(session.getId(), 6);
        String followUpHint = buildFollowUpHint(request.question(), session.getId());

        List<VectorRepository.SearchHit> hits = List.of();
        String answer = null;
        String goalDigest = plan.videoId() == null ? null : qaCheckpointService.digest(request.question());
        // WS3：SUMMARY 与 AGENT 两种可缓存路径才走 checkpoint（键仍为 (videoId, goalDigest)）
        boolean checkpointable = plan.videoId() != null
                && (plan.mode() == QuestionMode.SUMMARY || plan.backend() == Backend.AGENT);

        boolean reused = false;
        if (checkpointable) {
            QaCheckpoint done = qaCheckpointService.loadResult(plan.videoId(), goalDigest);
            if (done != null && done.getResult() != null && !done.getResult().isBlank()) {
                answer = done.getResult();
                reused = true;
            }
        }
        if (!reused) {
            switch (plan.mode()) {
                case SUMMARY -> {
                    // 全量归纳策略：map-reduce 汇总全片证据，避免被 top-K 锚定到局部
                    answer = summaryStrategyService.summarize(plan.videoId(), request.question(),
                            history, followUpHint);
                    qaCheckpointService.saveResult(plan.videoId(), goalDigest, answer);
                }
                case GENERAL, SPOT, VISUAL -> {
                    // FAST：确定性检索 + 单轮分级回答
                    hits = vectorSearchService.search(request.question(), plan.videoId(),
                            plan.textTopK(), plan.frameTopK(), plan.ocrTopK());
                    answer = answerAgent.generate(request.question(), buildContext(hits), history, followUpHint)
                            .answer();
                }
                case ANALYSIS -> {
                    // 语义编排通道：Planner 结构化规划 → Agent Runtime（执行 + Reviewer 回炉）
                    Plan agentPlan = plannerAgent.plan(request.question(), plan.mode().name(), history);
                    qaCheckpointService.savePlan(plan.videoId(), goalDigest, toJson(agentPlan));
                    answer = agentRuntime.run(request.question(), plan.videoId(),
                            plan.mode().name(), history, agentPlan, followUpHint);
                    // 中间态留档（草稿 + Reviewer 状态），为异步续跑铺路
                    if (answer != null) {
                        qaCheckpointService.saveExec(plan.videoId(), goalDigest, answer, null);
                    }
                }
                default -> throw new IllegalStateException("未处理的问题模式: " + plan.mode());
            }
        }

        List<ReferenceDto> refs = referenceParser.parse(answer, plan.videoId(), hits);
        // WS1：引用证据确定性校验（标注 verified/verifyNote + 返回失实引用说明）
        AnnotatedRefs annotated = annotateReferences(plan.videoId(), plan.mode(), refs);
        refs = annotated.refs();

        // AGENT 通道存在失实引用时，做一次定向修正后重新校验
        if (plan.backend() == Backend.AGENT && !annotated.unverifiedNotes().isEmpty()
                && plan.videoId() != null) {
            Plan replan = plannerAgent.plan(request.question(), plan.mode().name(), history);
            String corrected = agentRuntime.refineCitations(request.question(), answer, plan.videoId(),
                    history, replan, followUpHint, annotated.unverifiedNotes());
            if (corrected != null) {
                answer = corrected;
                List<ReferenceDto> refs2 = referenceParser.parse(answer, plan.videoId(), List.of());
                refs = annotateReferences(plan.videoId(), plan.mode(), refs2).refs();
            }
        }

        // WS3：SUMMARY/AGENT 最终结果落库（DONE，供重复问题幂等复用）
        if (checkpointable && !reused) {
            qaCheckpointService.saveResult(plan.videoId(), goalDigest, answer);
        }

        // 后处理：消息落库（会话记忆）
        chatSessionService.saveMessage(session.getId(), "user", request.question(), null);
        chatSessionService.saveMessage(session.getId(), "assistant", answer, toJson(refs));

        log.info("[qa] sessionId={} mode={} backend={} question={} refs={}",
                session.getId(), plan.mode(), plan.backend(), request.question(), refs.size());
        return new QaResponse(session.getId(), plan.videoId(), plan.backend().name(), answer, refs);
    }

    /** WS1：引用证据确定性校验结果 */
    private record AnnotatedRefs(List<ReferenceDto> refs, List<String> unverifiedNotes) {
    }

    /**
     * WS1：批量确定性校验（一次校验全部引用），回写 verified/verifyNote，并收集失实引用说明。
     * 对 SUMMARY：摘要改写后的引用只做时间区间覆盖校验，不强制文本 bigram 匹配（quote 以空传入），
     * 避免全片摘要被判失实。
     */
    private AnnotatedRefs annotateReferences(Long videoId, QuestionMode mode, List<ReferenceDto> refs) {
        if (videoId == null) {
            return new AnnotatedRefs(refs, List.of());
        }
        if (refs == null || refs.isEmpty()) {
            return new AnnotatedRefs(List.of(), List.of());
        }
        boolean relaxed = mode == QuestionMode.SUMMARY;
        List<VerificationSource> sources = new ArrayList<>(refs.size());
        for (ReferenceDto r : refs) {
            sources.add(new VerificationSource(r.type(), r.startSec(), r.endSec(),
                    relaxed ? null : r.quote()));
        }
        List<Verification> vs = referenceVerifier.verifyAll(videoId, sources);
        List<ReferenceDto> out = new ArrayList<>(refs.size());
        List<String> unverified = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            ReferenceDto r = refs.get(i);
            Verification v = vs.get(i);
            out.add(new ReferenceDto(r.videoId(), r.startSec(), r.endSec(), r.type(), r.quote(),
                    v.verified(), v.reason()));
            if (!v.verified()) {
                unverified.add("[" + r.type() + " " + (r.startSec() == null ? "?" : fmtSec(r.startSec()))
                        + "] " + v.reason());
            }
        }
        return new AnnotatedRefs(out, unverified);
    }

    private String fmtSec(Integer sec) {
        if (sec == null) {
            return "00:00";
        }
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    private String buildFollowUpHint(String question, Long sessionId) {
        // 追问/省略主语回指检测：短问或含回指词，且会话有上一轮
        int len = question.strip().length();
        boolean likelyFollowUp = len > 0 && len <= 30
                && FOLLOWUP_WORDS.stream().anyMatch(question::contains);
        if (!likelyFollowUp) {
            return null;
        }
        ChatSessionService.LastTurn last = chatSessionService.lastTurn(sessionId);
        if (last == null || last.question() == null || last.question().isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("你上一问是：").append(last.question());
        if (last.answer() != null && !last.answer().isBlank()) {
            sb.append("\n你上次的回答是：").append(last.answer());
        }
        sb.append("\n本次问题疑似省略主语/回指的追问，应围绕上一问展开作答，不要重新概括全片。");
        return sb.toString();
    }

    private String buildContext(List<VectorRepository.SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (VectorRepository.SearchHit hit : hits) {
            String label = switch (hit.type()) {
                case "FRAME" -> "（画面）";
                case "OCR" -> "（字幕）";
                default -> "（语音）";
            };
            sb.append('[').append(fmt(hit.startSec())).append('-').append(fmt(hit.endSec())).append(']')
                    .append(label).append('\n')
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

    private String toJson(Plan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            return "{}";
        }
    }
}
