package com.videoagent.business.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.storage.entity.QaCheckpoint;
import com.videoagent.storage.mapper.QaCheckpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 目标级问答 Checkpoint（WS3）：按 (video_id, goal_digest) 持久化问题分析过程的
 * Plan/Exec 草稿/Reviewer 状态与最终结果。MySQL 为真源、Redis 为热缓存（TTL 1h）。
 * 同一视频同一问题的 DONE 结果幂等复用，避免重复模型调用；中间态为将来问答异步续跑留档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaCheckpointService {

    private final QaCheckpointMapper checkpointMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "video-agent:qa-checkpoint:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /** 目标摘要：MD5(归一化问题)，同一问题稳定复用键 */
    public String digest(String question) {
        String norm = question == null ? "" : question.strip();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不会发生；兜底用 hashCode，保证不阻断主流程
            return Integer.toHexString(norm.hashCode());
        }
    }

    /** 读取已完成（DONE）结果；命中 Redis 回源 MySQL。无则返回 null */
    public QaCheckpoint loadResult(Long videoId, String goalDigest) {
        String cacheKey = cacheKey(videoId, goalDigest);
        QaCheckpoint cached = readCache(cacheKey);
        if (cached != null) {
            return QaCheckpoint.STAGE_DONE.equals(cached.getStage()) ? cached : null;
        }
        QaCheckpoint row = selectByKey(videoId, goalDigest);
        if (row != null) {
            writeCache(cacheKey, row);
        }
        return row != null && QaCheckpoint.STAGE_DONE.equals(row.getStage()) ? row : null;
    }

    /** 保存 Plan（PLANNED） */
    public void savePlan(Long videoId, String goalDigest, String planJson) {
        txn(videoId, goalDigest, cp -> {
            cp.setStage(QaCheckpoint.STAGE_PLANNED);
            cp.setPlanJson(planJson);
        });
    }

    /** 保存执行草稿 + Reviewer 状态（EXECUTED/REVIEWED） */
    public void saveExec(Long videoId, String goalDigest, String execDraft, String reviewerState) {
        txn(videoId, goalDigest, cp -> {
            cp.setStage(QaCheckpoint.STAGE_EXECUTED);
            cp.setExecDraft(execDraft);
            if (reviewerState != null) {
                cp.setReviewerState(reviewerState);
                cp.setStage(QaCheckpoint.STAGE_REVIEWED);
            }
        });
    }

    /** 保存最终结果（DONE） */
    public void saveResult(Long videoId, String goalDigest, String result) {
        txn(videoId, goalDigest, cp -> {
            cp.setStage(QaCheckpoint.STAGE_DONE);
            cp.setResult(result);
        });
    }

    /** 幂等 upsert：存在则更新，否则插入；写库后刷新缓存 */
    private void txn(Long videoId, String goalDigest, java.util.function.Consumer<QaCheckpoint> mutator) {
        if (videoId == null || goalDigest == null || goalDigest.isBlank()) {
            return;
        }
        QaCheckpoint cp = selectByKey(videoId, goalDigest);
        boolean insert = cp == null;
        if (cp == null) {
            cp = new QaCheckpoint();
            cp.setVideoId(videoId);
            cp.setGoalDigest(goalDigest);
            cp.setStage(QaCheckpoint.STAGE_PLANNED);
        }
        mutator.accept(cp);
        if (insert) {
            checkpointMapper.insert(cp);
        } else {
            checkpointMapper.updateById(cp);
        }
        writeCache(cacheKey(videoId, goalDigest), cp);
        log.info("[qa-checkpoint] save videoId={} digest={} stage={}", videoId, goalDigest, cp.getStage());
    }

    private QaCheckpoint selectByKey(Long videoId, String goalDigest) {
        return checkpointMapper.selectOne(new LambdaQueryWrapper<QaCheckpoint>()
                .eq(QaCheckpoint::getVideoId, videoId)
                .eq(QaCheckpoint::getGoalDigest, goalDigest)
                .last("limit 1"));
    }

    private String cacheKey(Long videoId, String digest) {
        return CACHE_PREFIX + videoId + ":" + digest;
    }

    private QaCheckpoint readCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, QaCheckpoint.class);
        } catch (Exception e) {
            log.warn("[qa-checkpoint] cache read fail key={} err={}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, QaCheckpoint cp) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(cp),
                    CACHE_TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[qa-checkpoint] cache write fail key={} err={}", key, e.getMessage());
        }
    }
}