package com.videoagent.business.harness;

import com.videoagent.access.auth.UserContext;
import com.videoagent.access.dto.QaRequest;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.business.agent.IntentClassifier;
import com.videoagent.business.agent.IntentClassifier.IntentResult;
import com.videoagent.storage.entity.ChatSession;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 确定性规则层（亮点 C 第一层）：毫秒级、可解释、先于 Agent 语义编排决策。
 * 重构核心：从「FAST/AGENT 双通道」改为「问题模式 → 回答策略（含检索方式）」，
 * 由 {@link QuestionMode} 决定回答策略，由 {@link Backend} 决定执行后端。
 * 问题模式不再由关键词判型，改由 {@link IntentClassifier}（LLM 意图识别）给出，
 * 匹配不准的同义改写问题得到解决；意图分类异常/限流时安全回退 GENERAL，不阻断主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleRouter {

    private final VideoMapper videoMapper;
    private final IntentClassifier intentClassifier;

    /** 通道（对外/历史兼容）：快通道（直连 RAG / 全量归纳）/ 语义编排通道（多 Agent） */
    public static final String CHANNEL_FAST = "FAST";
    public static final String CHANNEL_AGENT = "AGENT";

    /** 路由结果：问题模式 + 执行后端 + 检索参数 */
    public record StrategyPlan(QuestionMode mode, Backend backend, Long videoId,
                               int textTopK, int frameTopK, int ocrTopK) {
    }

    /**
     * 规则决策：
     * 规则1 视频范围：请求指定或会话关联 → 单视频；否则全局
     * 规则2 问题模式：概述/画面/时间/分析
     * 规则3 后端选择：ANALYSIS → AGENT；其余 → FAST
     * 规则4 检索参数：模式映射 topK 与 TEXT/FRAME 配比
     */
    public StrategyPlan route(QaRequest request, ChatSession session) {
        // 规则1：视频范围
        Long videoId = request.videoId() != null ? request.videoId()
                : (session != null ? session.getVideoId() : null);

        // 规则5：业务校验（目标视频必须处理就绪）
        if (videoId != null) {
            Video video = videoMapper.selectById(videoId);
            if (video == null) {
                throw new BusinessException("视频不存在: " + videoId);
            }
            if (!VideoStatus.READY.name().equals(video.getStatus())) {
                throw new BusinessException("视频尚未处理完成(状态=" + video.getStatus() + ")，请等待 READY 后提问");
            }
        }

        // 规则2：问题模式（LLM 意图识别，异常/限流安全回退 GENERAL）
        IntentResult intent = intentClassifier.classify(request.question(), UserContext.get());
        QuestionMode mode = intent.mode();
        // SUMMARY 要求单视频语境；全局（无 videoId）时回退为 GENERAL（FAST 全局检索/归纳）
        if (mode == QuestionMode.SUMMARY && videoId == null) {
            mode = QuestionMode.GENERAL;
        }

        // 规则3：后端选择
        Backend backend = mode == QuestionMode.ANALYSIS ? Backend.AGENT : Backend.FAST;

        // 规则4：检索参数
        int textTopK = 8;
        int frameTopK = 4;
        int ocrTopK = 3;
        switch (mode) {
            case VISUAL -> {
                textTopK = 6;
                frameTopK = 8;
                ocrTopK = 6;
            }
            case SPOT -> {
                textTopK = 10;
                frameTopK = 4;
            }
            case ANALYSIS -> {
                textTopK = 10;
                frameTopK = 6;
                ocrTopK = 4;
            }
            default -> {
            }
        }

        log.info("[rule] mode={} backend={} videoId={} textTopK={} frameTopK={} ocrTopK={} intentReason={}",
                mode, backend, videoId, textTopK, frameTopK, ocrTopK, intent.reason());
        return new StrategyPlan(mode, backend, videoId, textTopK, frameTopK, ocrTopK);
    }
}