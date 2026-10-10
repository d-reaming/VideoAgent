package com.videoagent.business.strategy;

import com.videoagent.business.agent.AnswerAgent;
import com.videoagent.business.agent.GlobalContextTool;
import com.videoagent.business.evidence.Evidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 概述（SUMMARY）问答策略：map-reduce 全量归纳。
 * 复用 GlobalContextTool.build() 取单视频全量证据（META/AUDIO/VISION/OCR/校订，时间升序），
 * 按 CHUNK_LEN 分块后逐块调用 AnswerAgent.generate 出要点（map），块数≥2 时二次调用汇总（reduce）。
 * 全部复用项目已有答案生成器，不新建 LLM 客户端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryStrategyService {

    /** 单块字符数上限：对齐项目 GROUND_TRUTH_CAP 思路，避免单次调用超上下文 */
    static final int CHUNK_LEN = 4000;

    private final GlobalContextTool globalContextTool;
    private final AnswerAgent answerAgent;

    public String summarize(Long videoId, String question, String historyText, String followUpHint) {
        List<Evidence> evidence = globalContextTool.build(videoId);
        String full = evidence.stream().map(Evidence::toText)
                .collect(Collectors.joining("\n"));

        // map：按块生成要点
        List<String> chunks = chunk(full, CHUNK_LEN);
        List<String> digests = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            digests.add(answerAgent.generate(question, chunk, historyText, followUpHint).answer());
        }
        if (digests.size() <= 1) {
            return digests.isEmpty() ? "" : digests.get(0);
        }
        // reduce：汇总各块要点
        String mergeContext = digests.stream().map(d -> "- " + d)
                .collect(Collectors.joining("\n"));
        String merged = answerAgent.generate(question, mergeContext, historyText, followUpHint).answer();
        log.info("[summary-strategy] videoId={} chunks={} merged=true", videoId, digests.size());
        return merged;
    }

    /** 纯函数：按 len 截断分块（供单测断言） */
    static List<String> chunk(String text, int len) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || len <= 0) {
            return out;
        }
        for (int i = 0; i < text.length(); i += len) {
            out.add(text.substring(i, Math.min(text.length(), i + len)));
        }
        return out;
    }
}