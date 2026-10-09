package com.videoagent.business.harness;

import com.videoagent.access.dto.QaRequest;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.storage.entity.ChatSession;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 确定性规则层（亮点 C 第一层）：毫秒级、可解释、先于 Agent 语义编排决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleRouter {

    private final VideoMapper videoMapper;

    /** 通道：快通道（直连 RAG） / 语义编排通道（多 Agent） */
    public static final String CHANNEL_FAST = "FAST";
    public static final String CHANNEL_AGENT = "AGENT";

    private static final List<String> VISUAL_WORDS = List.of("画面", "图表", "图 ", "图像", "屏幕", "看到", "显示",
            "按钮", "界面", "截图", "图片", "海报", "文字内容", "表格");
    private static final List<String> COMPLEX_WORDS = List.of("对比", "总结", "分析", "为什么", "如何", "区别",
            "综述", "评估", "梳理", "归纳", "一共", "所有", "讲了什么", "讲什么", "讲了啥", "讲啥", "说的什么", "说了什么",
            "说了啥", "说啥", "讲的什么", "摘要", "大意", "全文", "概述", "整体", "主要", "内容是什么", "视频内容",
            "内容概括", "内容大意", "主题", "在讲什么", "在讲啥", "重点", "讲的内容");
    private static final Pattern TIME_PATTERN = Pattern.compile("(第\\s*\\d+\\s*分钟|几(分|秒)钟|多长时间|时间段|"
            + "在.*(?:分钟|秒)|几点)");

    public record RuleResult(String channel, Long videoId, int textTopK, int frameTopK, int ocrTopK,
                             String questionType) {
    }

    /**
     * 规则决策：
     * 规则1 视频范围：请求指定或会话关联 → 单视频；否则全局
     * 规则2 问题类型：画面类/时间类/综合类
     * 规则3 通道选择：综合复杂 → AGENT；简单 → FAST
     * 规则4 检索参数：类型映射 topK 与 TEXT/FRAME 配比
     */
    public RuleResult route(QaRequest request, ChatSession session) {
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

        // 规则2：问题类型
        String q = request.question().toLowerCase(Locale.ROOT);
        String questionType = "GENERAL";
        if (COMPLEX_WORDS.stream().anyMatch(q::contains)) {
            questionType = "COMPLEX";
        } else if (TIME_PATTERN.matcher(q).find()) {
            questionType = "TEMPORAL";
        } else if (VISUAL_WORDS.stream().anyMatch(q::contains)) {
            questionType = "VISUAL";
        }

        // 规则3：通道选择
        String channel;
        if ("COMPLEX".equals(questionType)) {
            channel = CHANNEL_AGENT;
        } else if (request.question().length() > 50) {
            channel = CHANNEL_AGENT;
        } else {
            channel = CHANNEL_FAST;
        }

        // 规则4：检索参数
        int textTopK = 8;
        int frameTopK = 4;
        int ocrTopK = 3;
        switch (questionType) {
            case "VISUAL" -> {
                textTopK = 6;
                frameTopK = 8;
                ocrTopK = 6;
            }
            case "TEMPORAL" -> {
                textTopK = 10;
                frameTopK = 4;
            }
            case "COMPLEX" -> {
                textTopK = 10;
                frameTopK = 6;
                ocrTopK = 4;
            }
            default -> {
            }
        }

        log.info("[rule] channel={} type={} videoId={} textTopK={} frameTopK={} ocrTopK={}",
                channel, questionType, videoId, textTopK, frameTopK, ocrTopK);
        return new RuleResult(channel, videoId, textTopK, frameTopK, ocrTopK, questionType);
    }
}
