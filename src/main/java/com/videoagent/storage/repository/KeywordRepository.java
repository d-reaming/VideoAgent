package com.videoagent.storage.repository;

import com.videoagent.access.auth.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 关键词倒排表读写（video_keyword 表，PG）。
 * 多租户与 VectorRepository 同策略：索引写入传 userId；检索按 {@link UserContext} 当前属主过滤。
 */
@Repository
@RequiredArgsConstructor
public class KeywordRepository {

    private final JdbcTemplate pgJdbcTemplate;

    /** 按 (video_id, token, chunk_type, seq) 幂等 upsert 分批写倒排 */
    public void upsert(Long videoId, Long userId, String token, String type, int seq,
                       Integer startSec, Integer endSec, int freq) {
        pgJdbcTemplate.update(
                "INSERT INTO video_keyword (video_id, user_id, token, chunk_type, seq, start_sec, end_sec, freq) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (video_id, token, chunk_type, seq) DO UPDATE SET "
                        + "user_id=EXCLUDED.user_id, start_sec=EXCLUDED.start_sec, end_sec=EXCLUDED.end_sec, freq=EXCLUDED.freq",
                videoId, userId == null ? 0L : userId, token, type, seq, startSec, endSec, freq);
    }

    /**
     * 关键词检索：命中块（chunk-level 归并，score=匹配 token 频次和）。
     */
    public List<VectorRepository.SearchHit> search(List<String> tokens, Long videoId, int topK) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT vk.video_id, vk.chunk_type, vk.start_sec, vk.end_sec, vc.content, SUM(vk.freq) AS kwscore
                FROM video_keyword vk
                JOIN video_chunk vc
                  ON vc.video_id = vk.video_id AND vc.chunk_type = vk.chunk_type AND vc.seq = vk.seq
                WHERE vk.token = ANY (?)
                """);
        // token 列表以单个 PG 数组参数绑定（= ANY(?)），避免长 IN 列表的位置占位符问题
        List<Object> params = new java.util.ArrayList<>(tokens.size() + 3);
        params.add(tokens.toArray(new String[0]));
        Long uid = UserContext.get();
        if (uid != null) {
            sql.append(" AND vk.user_id = ?");
            params.add(uid);
        }
        if (videoId != null) {
            sql.append(" AND vk.video_id = ?");
            params.add(videoId);
        }
        sql.append(" GROUP BY vk.video_id, vk.chunk_type, vk.seq, vk.start_sec, vk.end_sec, vc.content")
                .append(" ORDER BY kwscore DESC LIMIT ?");
        params.add(topK);

        return pgJdbcTemplate.query(sql.toString(), (rs, rowNum) -> new VectorRepository.SearchHit(
                        rs.getLong("video_id"),
                        rs.getString("chunk_type"),
                        rs.getObject("start_sec", Integer.class),
                        rs.getObject("end_sec", Integer.class),
                        rs.getString("content"),
                        rs.getInt("kwscore")),
                params.toArray());
    }

    public void deleteByVideoId(Long videoId) {
        pgJdbcTemplate.update("DELETE FROM video_keyword WHERE video_id = ?", videoId);
    }

    /** 该视频已入倒排的行数（0 表示尚未建倒排） */
    public long countByVideoId(Long videoId) {
        Long n = pgJdbcTemplate.queryForObject(
                "SELECT count(*) FROM video_keyword WHERE video_id = ?", Long.class, videoId);
        return n == null ? 0L : n;
    }
}