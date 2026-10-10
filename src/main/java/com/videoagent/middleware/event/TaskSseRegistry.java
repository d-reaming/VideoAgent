package com.videoagent.middleware.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务阶段 SSE 推送注册表（WS5）：按 videoId 维护存活 SseEmitter，管道阶段变化时推送进度；
 * 断连/超时时自动移除。重连时由订阅方自行先拉 progress() 对齐当前状态。
 */
@Slf4j
@Component
public class TaskSseRegistry {

    private static final long CONNECT_TIMEOUT_MS = 0; // 服务端不主动超时，交给客户端
    private static final long HEARTBEAT_MS = 30_000;

    private final Map<Long, Map<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 订阅：注册 emitter，推送一条"已订阅"事件后返回（用于重连时客户端收到握手）。 */
    public SseEmitter subscribe(Long videoId) {
        SseEmitter emitter = new SseEmitter(CONNECT_TIMEOUT_MS);
        String id = Long.toString(System.identityHashCode(emitter));
        emitters.computeIfAbsent(videoId, k -> new ConcurrentHashMap<>()).put(id, emitter);
        emitter.onCompletion(() -> remove(videoId, id));
        emitter.onTimeout(() -> remove(videoId, id));
        emitter.onError(e -> remove(videoId, id));
        try {
            emitter.send(SseEmitter.event().name("subscribed").data("ok"));
        } catch (IOException e) {
            remove(videoId, id);
        }
        return emitter;
    }

    /** 推送给某视频所有订阅者；单点失败仅移除该连接，不影响其它。 */
    public void publish(Long videoId, Object payload) {
        Map<String, SseEmitter> map = emitters.get(videoId);
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SseEmitter> e : map.entrySet()) {
            try {
                e.getValue().send(SseEmitter.event().name("progress").data(payload));
            } catch (Exception ex) {
                log.warn("[sse] push failed videoId={} id={} err={}", videoId, e.getKey(), ex.getMessage());
                map.remove(e.getKey());
            }
        }
    }

    private void remove(Long videoId, String id) {
        Map<String, SseEmitter> map = emitters.get(videoId);
        if (map != null) {
            map.remove(id);
            if (map.isEmpty()) {
                emitters.remove(videoId);
            }
        }
    }
}