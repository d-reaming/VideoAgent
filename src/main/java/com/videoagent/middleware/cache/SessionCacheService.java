package com.videoagent.middleware.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.business.harness.ChatSessionService.SessionSummary;
import com.videoagent.storage.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 会话快取：Redis 只做「高频读」的便捷取用层，MySQL 仍是全量备份/唯一权威（cache-through）。
 * 读路径命中缓存直接返回；未命中回源 DB 并回填。写路径同步 MySQL 后失效对应缓存，下次读回源重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCacheService {

    private static final String MSG_KEY = "video-agent:session:messages:";
    private static final String LIST_KEY = "video-agent:session:list:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 会话消息列表（升序）：缓存命中直接返回，否则回源 DB 并回填 */
    public List<ChatMessage> messages(Long sessionId, List<ChatMessage> fallback) {
        String key = MSG_KEY + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            try {
                return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {
                });
            } catch (Exception e) {
                log.warn("[sess-cache] read messages fail sessionId={} err={}", sessionId, e.getMessage());
            }
        }
        if (fallback == null) {
            return null; // 未命中且无可回填数据
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fallback),
                    TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[sess-cache] write messages fail sessionId={} err={}", sessionId, e.getMessage());
        }
        return fallback;
    }

    /** 新增一条消息后失效该会话的消息缓存 */
    public void invalidateMessages(Long sessionId) {
        redisTemplate.delete(MSG_KEY + sessionId);
    }

    /** 会话摘要列表（某视频下）：缓存读 + 仅未命中时执行 Supplier 回源回填 */
    public List<SessionSummary> listByVideo(Long videoId, Supplier<List<SessionSummary>> loader) {
        String key = LIST_KEY + videoId;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            try {
                return objectMapper.readValue(json, new TypeReference<List<SessionSummary>>() {
                });
            } catch (Exception e) {
                log.warn("[sess-cache] read list fail videoId={} err={}", videoId, e.getMessage());
            }
        }
        List<SessionSummary> fallback = loader.get();
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fallback),
                    TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[sess-cache] write list fail videoId={} err={}", videoId, e.getMessage());
        }
        return fallback;
    }

    /** 新增会话后失效该视频的会话列表缓存 */
    public void invalidateSessionList(Long videoId) {
        if (videoId != null) {
            redisTemplate.delete(LIST_KEY + videoId);
        }
    }
}