package com.videoagent.business.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.access.auth.UserContext;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.cache.SessionCacheService;
import com.videoagent.storage.entity.ChatMessage;
import com.videoagent.storage.entity.ChatSession;
import com.videoagent.storage.mapper.ChatMessageMapper;
import com.videoagent.storage.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 多轮会话服务：会话创建、消息存取、历史组装。
 * MySQL 为全量权威；Redis 通过 SessionCacheService 做高频读的便捷取用。
 */
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    /** 消息缓存窗口：只缓存最近 N 条，避免列表无限增长 */
    private static final int CACHE_WINDOW = 60;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final SessionCacheService sessionCache;

    /** 获取或创建会话（videoId 关联用于规则1 单视频范围）；多租户：会话归属当前用户 */
    public ChatSession getOrCreate(Long sessionId, Long videoId) {
        if (sessionId != null) {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                throw new BusinessException("会话不存在: " + sessionId);
            }
            checkOwner(session);
            return session;
        }
        ChatSession session = new ChatSession();
        session.setVideoId(videoId);
        session.setUserId(currentUid());
        session.setTitle("问答会话");
        sessionMapper.insert(session);
        sessionCache.invalidateSessionList(videoId);
        return session;
    }

    /** 校验并返回当前用户拥有的会话（不存在/非属主抛错） */
    public ChatSession owned(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在: " + sessionId);
        }
        checkOwner(session);
        return session;
    }

    private void checkOwner(ChatSession session) {
        Long uid = currentUid();
        // 严格属主：user_id 为 null/0（存量）会话不可被普通用户访问/追加
        if (session.getUserId() == null || !session.getUserId().equals(uid)) {
            throw new BusinessException(403, "无权访问该会话");
        }
    }

    private Long currentUid() {
        Long uid = UserContext.get();
        if (uid == null) {
            throw new BusinessException(401, "未登录");
        }
        return uid;
    }

    public void saveMessage(Long sessionId, String role, String content, String refsJson) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setRefs(refsJson);
        messageMapper.insert(message);
        sessionCache.invalidateMessages(sessionId);
    }

    /** 最近 N 轮历史消息（升序）：读 Redis 最近窗口缓存，未命中回源 DB 并回填 */
    public List<ChatMessage> history(Long sessionId, int limit) {
        List<ChatMessage> cached = sessionCache.messages(sessionId, null);
        if (cached != null) {
            return sliceLast(cached, limit);
        }
        // 回源 DB：取最近 CACHE_WINDOW 条（倒序）再翻转为升序
        List<ChatMessage> recentDesc = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getId)
                .last("limit " + CACHE_WINDOW));
        java.util.Collections.reverse(recentDesc);
        List<ChatMessage> recentAsc = recentDesc;
        sessionCache.messages(sessionId, recentAsc);
        return sliceLast(recentAsc, limit);
    }

    private List<ChatMessage> sliceLast(List<ChatMessage> list, int limit) {
        return list.stream().skip(Math.max(0, list.size() - limit)).toList();
    }

    /** 某视频下的会话摘要（按创建倒序，用于前端会话切换）：仅当前用户、Redis 取用 + DB 回源 */
    public List<SessionSummary> listByVideo(Long videoId) {
        Long uid = currentUid();
        return sessionCache.listByVideo(videoId, () -> sessionMapper.selectList(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getVideoId, videoId)
                                .eq(ChatSession::getUserId, uid)
                                .orderByDesc(ChatSession::getId))
                .stream()
                .map(s -> new SessionSummary(s.getId(), s.getTitle(), s.getCreatedAt(),
                        Math.toIntExact(messageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getSessionId, s.getId())))))
                .toList());
    }

    public record SessionSummary(Long id, String title, LocalDateTime createdAt, int messageCount) {
    }

    /** 历史文本（拼接到 Agent 上下文） */
    public String historyText(Long sessionId, int limit) {
        List<ChatMessage> history = history(sessionId, limit);
        StringBuilder sb = new StringBuilder();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            sb.append(m.getRole().equals("user") ? "用户: " : "助手: ").append(m.getContent()).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 上一轮对话（用户问 + 助手答），用于追问/省略主语回指的关联注入。
     * history() 返回升序，取最近一条 user 及其后的第一条 assistant。
     */
    public LastTurn lastTurn(Long sessionId) {
        List<ChatMessage> history = history(sessionId, 6);
        ChatMessage lastUser = null;
        for (ChatMessage m : history) {
            if ("user".equals(m.getRole())) {
                lastUser = m;
            }
        }
        if (lastUser == null) {
            return new LastTurn(null, null);
        }
        String answer = null;
        int idx = history.indexOf(lastUser);
        for (int i = idx + 1; i < history.size(); i++) {
            if ("assistant".equals(history.get(i).getRole())) {
                answer = history.get(i).getContent();
                break;
            }
        }
        return new LastTurn(lastUser.getContent(), answer);
    }

    public record LastTurn(String question, String answer) {
    }
}
