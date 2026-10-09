package com.videoagent.middleware.rag;

import com.videoagent.storage.repository.KeywordRepository;
import com.videoagent.storage.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索服务：向量(TEXT/FRAME) + 关键词倒排混合召回，时间聚类去重 + 分数重排。
 * 混合策略：倒排精确命中块在向量分上加成；未进向量 topK 的纯关键词命中作兜底进入候选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    /** 时间聚类窗口（秒）：命中间隔小于该值视为同一片段 */
    private static final int CLUSTER_WINDOW = 30;
    /** 命中倒排的向量块额外加分权重 */
    private static final double KEYWORD_BOOST = 0.6;
    /** 纯关键词命中（未进入向量 topK）的基础分，保证精确召回能浮上来 */
    private static final double KEYWORD_FLOOR = 0.45;
    /** 关键词频次归一化除数：f(x)=min(x/NORM, 1) */
    private static final int KEYWORD_NORM = 4;

    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final KeywordRepository keywordRepository;
    private final KeyWordTokenizer keyWordTokenizer;

    /**
     * 联合检索（三通道：语音 TEXT + 画面 FRAME + 字幕 OCR）。
     *
     * @param question    问题
     * @param videoId     目标视频（null=全局）
     * @param textTopK    文本块数量
     * @param frameTopK   画面块数量
     * @param ocrTopK     字幕块数量
     */
    public List<VectorRepository.SearchHit> search(String question, Long videoId, int textTopK, int frameTopK, int ocrTopK) {
        float[] queryVec = embeddingService.embed(question);

        List<VectorRepository.SearchHit> textHits =
                vectorRepository.search(queryVec, videoId, "TEXT", textTopK);
        List<VectorRepository.SearchHit> frameHits =
                vectorRepository.search(queryVec, videoId, "FRAME", frameTopK);
        List<VectorRepository.SearchHit> ocrHits =
                vectorRepository.search(queryVec, videoId, "OCR", ocrTopK);

        // 倒排精确召回：命中块按 (video,type,startSec) 归并 → 给对应向量块加成
        List<String> tokens = new ArrayList<>(keyWordTokenizer.tokenize(question).keySet());
        List<VectorRepository.SearchHit> kwHits =
                keywordRepository.search(tokens, videoId, textTopK + frameTopK + ocrTopK);
        Map<String, Double> boost = new HashMap<>();
        Set<String> covered = new HashSet<>();
        for (VectorRepository.SearchHit h : kwHits) {
            boost.put(key(h), Math.min(1.0, h.score() / KEYWORD_NORM));
        }

        List<VectorRepository.SearchHit> merged = new ArrayList<>();
        for (VectorRepository.SearchHit h : textHits) {
            covered.add(key(h));
            merged.add(bump(h, boost));
        }
        for (VectorRepository.SearchHit h : frameHits) {
            covered.add(key(h));
            merged.add(bump(h, boost));
        }
        for (VectorRepository.SearchHit h : ocrHits) {
            covered.add(key(h));
            merged.add(bump(h, boost));
        }
        // 语义召回不足时的精确兜底：未进入向量 topK 的纯关键词命中
        for (VectorRepository.SearchHit h : kwHits) {
            if (!covered.contains(key(h))) {
                double norm = Math.min(1.0, h.score() / KEYWORD_NORM);
                merged.add(new VectorRepository.SearchHit(h.videoId(), h.type(), h.startSec(), h.endSec(),
                        h.content(), KEYWORD_FLOOR + KEYWORD_BOOST * norm));
            }
        }
        return clusterAndRerank(merged, textTopK + frameTopK + ocrTopK);
    }

    /** 向量分 + 倒排命中加成 */
    private VectorRepository.SearchHit bump(VectorRepository.SearchHit h, Map<String, Double> boost) {
        double bonus = boost.getOrDefault(key(h), 0.0);
        if (bonus == 0.0) {
            return h;
        }
        return new VectorRepository.SearchHit(h.videoId(), h.type(), h.startSec(), h.endSec(), h.content(),
                h.score() + KEYWORD_BOOST * bonus);
    }

    private String key(VectorRepository.SearchHit h) {
        return h.videoId() + "|" + h.type() + "|" + (h.startSec() == null ? 0 : h.startSec());
    }

    /** 按时间聚类去重 + 分数重排 */
    private List<VectorRepository.SearchHit> clusterAndRerank(
            List<VectorRepository.SearchHit> hits, int limit) {
        List<VectorRepository.SearchHit> sorted =
                new ArrayList<>(hits.stream().sorted(Comparator.comparingInt(
                        h -> h.startSec() == null ? Integer.MAX_VALUE : h.startSec())).toList());

        List<VectorRepository.SearchHit> clustered = new ArrayList<>();
        for (VectorRepository.SearchHit hit : sorted) {
            VectorRepository.SearchHit last = clustered.isEmpty() ? null
                    : clustered.get(clustered.size() - 1);
            if (last != null && Math.abs(sec(last) - sec(hit)) < CLUSTER_WINDOW) {
                // 同一时间窗口：保留分数更高者
                if (hit.score() > last.score()) {
                    clustered.set(clustered.size() - 1, hit);
                }
            } else {
                clustered.add(hit);
            }
        }
        return clustered.stream()
                .sorted(Comparator.comparingDouble(VectorRepository.SearchHit::score).reversed())
                .limit(limit)
                .toList();
    }

    private int sec(VectorRepository.SearchHit hit) {
        return hit.startSec() == null ? Integer.MAX_VALUE : hit.startSec();
    }
}
