package com.videoagent.middleware.rag;

import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.LlmProperties;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.mapper.VideoMapper;
import com.videoagent.storage.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引服务：切块 → Embedding → PGVector 入库（离线流水线 EMBEDDING 阶段）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final KeywordIndexService keywordIndexService;
    private final LlmProperties llm;
    private final VideoMapper videoMapper;

    public static final int INDEX_BATCH = 50;

    public void process(Long videoId) {
        if (llm.dashscope().apiKey() == null || llm.dashscope().apiKey().isBlank()) {
            throw new BusinessException("未配置 DASHSCOPE_API_KEY，无法生成向量");
        }
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在: " + videoId);
        }
        Long userId = video.getUserId() == null ? 0L : video.getUserId();
        List<Chunk> chunks = chunkingService.buildChunks(videoId);
        int total = chunks.size();

        // 从上次已提交的 checkpoint 续传（buildChunks 顺序确定，全局下标稳定）
        int idx = video.getIndexedSeq() == null ? 0 : (int) Math.min(Math.max(video.getIndexedSeq(), 0), total);
        int resumeFrom = idx;
        while (idx < total) {
            int end = Math.min(idx + INDEX_BATCH, total);
            List<Chunk> batch = chunks.subList(idx, end);

            // 断点续传幂等：查询本批已存在向量行的 seq，只对「缺失」的 chunk 调用 Embedding。
            // chunk 内容由切块确定性生成，同视频重跑内容不变，跳过已有 chunk 不会造成内容失配。
            // seq 在 TEXT/FRAME 各自独立从 0 递增，故按 chunk_type 分组分别查存在性。
            Map<String, List<Chunk>> byType = batch.stream()
                    .collect(Collectors.groupingBy(Chunk::type));
            List<Chunk> toEmbed = new ArrayList<>();
            for (Map.Entry<String, List<Chunk>> e : byType.entrySet()) {
                List<Integer> seqs = e.getValue().stream().map(Chunk::seq).toList();
                Set<Integer> exist = vectorRepository.existingSeqs(videoId, e.getKey(), seqs);
                e.getValue().stream().filter(c -> !exist.contains(c.seq())).forEach(toEmbed::add);
            }
            if (!toEmbed.isEmpty()) {
                List<String> texts = toEmbed.stream().map(Chunk::content).toList();
                List<float[]> vectors = embeddingService.embedAll(texts);
                for (int i = 0; i < toEmbed.size(); i++) {
                    Chunk chunk = toEmbed.get(i);
                    vectorRepository.insert(Long.parseLong(chunk.videoId()), userId, chunk.type(), chunk.seq(),
                            chunk.startSec(), chunk.endSec(), chunk.content(), chunk.metadata(), vectors.get(i));
                }
            }
            // 同一批同步建关键词倒排（与向量用同一断点，一批内幂等；无 LLM，全批保留）
            keywordIndexService.indexBatch(batch, userId);
            // 每成功入选一批就持久化 checkpoint；写失败仅告警并继续推进内存进度，
            // 靠向量/倒排 upsert 幂等 + 下一轮从同断点续算兜底，避免单点写失败误杀整条流水线
            try {
                Video upd = new Video();
                upd.setId(videoId);
                upd.setIndexedSeq((long) end);
                videoMapper.updateById(upd);
            } catch (Exception e) {
                log.warn("[index] videoId={} checkpoint 写入失败，本批数据仍已入库，依赖幂等续传兜底: {}",
                        videoId, e.getMessage());
            }
            idx = end;
            log.info("[index] videoId={} 已入库 {}/{} 块", videoId, end, total);
        }
        log.info("[index] videoId={} chunks={} vectors={} resumeFrom={}", videoId, total,
                vectorRepository.countByVideoId(videoId), resumeFrom);
    }
}
