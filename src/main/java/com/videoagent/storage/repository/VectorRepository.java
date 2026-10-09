package com.videoagent.storage.repository;

import com.videoagent.access.auth.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * PGVector 向量库读写（video_chunk 表）。
 * 多租户：写入带 user_id；检索按 {@link UserContext} 当前属主过滤（整个问答链路为同一请求线程的同步调用，
 * ThreadLocal 在此天然有效）。非请求线程（如后台索引）不触发 user_id 过滤。
 */
@Repository
@RequiredArgsConstructor
public class VectorRepository {

    private final JdbcTemplate pgJdbcTemplate;

    /** 检索命中块 */
    public record SearchHit(Long videoId, String type, Integer startSec, Integer endSec, String content,
                            double score) {
    }

    public void deleteByVideoId(Long videoId) {
        pgJdbcTemplate.update("DELETE FROM video_chunk WHERE video_id = ?", videoId);
    }

    public void insert(Long videoId, Long userId, String type, int seq, Integer startSec, Integer endSec,
                       String content, String metadata, float[] embedding) {
        // 幂等 upsert：(video_id, chunk_type, seq) 冲突时覆盖。
        // 配合 Embedding 断点续传：跨数据源(PG向量/MySQL checkpoint)非事务，重复批次回放时靠它去重，不做全量 delete。
        pgJdbcTemplate.update(
                "INSERT INTO video_chunk (video_id, user_id, chunk_type, seq, start_sec, end_sec, content, metadata, embedding) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector) "
                        + "ON CONFLICT (video_id, chunk_type, seq) DO UPDATE SET "
                        + "user_id=EXCLUDED.user_id, start_sec=EXCLUDED.start_sec, end_sec=EXCLUDED.end_sec, "
                        + "content=EXCLUDED.content, metadata=EXCLUDED.metadata, embedding=EXCLUDED.embedding",
                videoId, userId == null ? 0L : userId, type, seq, startSec, endSec, content,
                metadata == null ? "{}" : metadata, toVectorString(embedding));
    }

    /** 向量检索：可选 videoId 过滤、type 过滤（TEXT/FRAME）、属主过滤 */
    public List<SearchHit> search(float[] queryVector, Long videoId, String type, int topK) {
        StringBuilder sql = new StringBuilder("""
                SELECT video_id, chunk_type, start_sec, end_sec, content,
                       1 - (embedding <=> ?::vector) AS score
                FROM video_chunk WHERE 1=1
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(toVectorString(queryVector));
        Long uid = UserContext.get();
        if (uid != null) {
            sql.append(" AND user_id = ?");
            params.add(uid);
        }
        if (videoId != null) {
            sql.append(" AND video_id = ?");
            params.add(videoId);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND chunk_type = ?");
            params.add(type);
        }
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(toVectorString(queryVector));
        params.add(topK);

        return pgJdbcTemplate.query(sql.toString(), (rs, rowNum) -> new SearchHit(
                        rs.getLong("video_id"),
                        rs.getString("chunk_type"),
                        rs.getObject("start_sec", Integer.class),
                        rs.getObject("end_sec", Integer.class),
                        rs.getString("content"),
                        rs.getDouble("score")),
                params.toArray());
    }

    public long countByVideoId(Long videoId) {
        Long count = pgJdbcTemplate.queryForObject(
                "SELECT count(*) FROM video_chunk WHERE video_id = ?", Long.class, videoId);
        return count == null ? 0 : count;
    }

    /**
     * 查询某视频某类 chunk 中「已存在向量行」的 seq 集合，供断点续传时跳过已入库块，
     * 避免对已生成向量的 chunk 重复调用昂贵的 Embedding。seq 以单个 PG 数组参数绑定（seq = ANY(?)），
     * 避免长 IN 列表的位置占位符问题（与 KeywordRepository 同一模式）。
     */
    public Set<Integer> existingSeqs(Long videoId, String type, List<Integer> seqs) {
        if (seqs == null || seqs.isEmpty()) {
            return Set.of();
        }
        return pgJdbcTemplate.query(
                "SELECT seq FROM video_chunk WHERE video_id = ? AND chunk_type = ? AND seq = ANY(?)",
                rs -> {
                    Set<Integer> set = new java.util.HashSet<>();
                    while (rs.next()) {
                        set.add(rs.getInt("seq"));
                    }
                    return set;
                },
                videoId, type, seqs.toArray(new Integer[0]));
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
