package com.videoagent.middleware.rag;

import com.videoagent.storage.repository.KeywordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 关键词分批入库：为一批 {@link Chunk} 分词并幂等写入倒排表（与向量分批同节奏，共用 indexed_seq 断点）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordIndexService {

    private final KeyWordTokenizer tokenizer;
    private final KeywordRepository keywordRepository;

    /** 为一批块建倒排索引（幂等，可重复回放） */
    public void indexBatch(List<Chunk> batch, long userId) {
        for (Chunk chunk : batch) {
            Map<String, Integer> tokens = tokenizer.tokenize(chunk.content());
            for (Map.Entry<String, Integer> e : tokens.entrySet()) {
                try {
                    keywordRepository.upsert(Long.parseLong(chunk.videoId()), userId, e.getKey(), chunk.type(), chunk.seq(),
                            chunk.startSec(), chunk.endSec(), e.getValue());
                } catch (RuntimeException ex) {
                    log.error("[kwindex] upsert 失败 video={} type={} seq={} token={} freq={}",
                            chunk.videoId(), chunk.type(), chunk.seq(), e.getKey(), e.getValue(), ex);
                    throw ex;
                }
            }
        }
    }
}