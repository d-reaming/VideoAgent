package com.videoagent.middleware.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.VideoMapper;
import com.videoagent.storage.repository.KeywordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存量视频关键词回填：为已向量化(READY)但因早期版本未建倒排索引的历史视频，补齐 video_keyword。
 * - 复用 ChunkingService rebuild chunk（与主流程 EMBEDDING 同一切块逻辑，结果确定且与已向量化块一致），仅建倒排、不重新 embedding。
 * - 幂等：按 video_id 是否已存在倒排行判断，可整体重跑（无需频次时后续 upsert 幂等）。
 * - 不动 indexed_seq（那是 EMBEDDING 阶段 checkpoint，存量 READY 视频可能为 0 但已向量化，避免污染导致重检）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordBackfillService {

    private final VideoMapper videoMapper;
    private final ChunkingService chunkingService;
    private final KeywordIndexService keywordIndexService;
    private final KeywordRepository keywordRepository;

    /** 回填全部 READY 但缺倒排的视频，返回补建视频数量 */
    public int backfill() {
        List<Video> videos = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .eq(Video::getStatus, VideoStatus.READY.name())
                .isNull(Video::getDeletedAt));
        int filled = 0;
        for (Video v : videos) {
            // 幂等守卫：该视频已存在倒排行即视为已回填，跳过
            if (keywordRepository.countByVideoId(v.getId()) > 0) {
                continue;
            }
            List<Chunk> chunks;
            try {
                chunks = chunkingService.buildChunks(v.getId());
            } catch (Exception e) {
                log.warn("[kwbackfill] videoId={} 重建 chunk 失败，跳过: {}", v.getId(), e.getMessage());
                continue;
            }
            if (chunks.isEmpty()) {
                continue;
            }
            Long userId = v.getUserId() == null ? 0L : v.getUserId();
            keywordIndexService.indexBatch(chunks, userId);
            filled++;
            log.info("[kwbackfill] videoId={} 补建倒排 chunks={}", v.getId(), chunks.size());
        }
        log.info("[kwbackfill] 完成，回填视频 {} 个", filled);
        return filled;
    }
}