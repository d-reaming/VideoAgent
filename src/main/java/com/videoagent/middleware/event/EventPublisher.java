package com.videoagent.middleware.event;

import com.videoagent.access.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 流水线事件发布器。
 * 可靠性：同步发送等待 broker ack + 有界重试（指数退避）；持续失败抛 {@link BusinessException}，
 * 使调用方（流水线 runStage）落到 task FAILED，从而事件不会静默丢失、可由手动 retry 补偿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private static final int MAX_RETRY = 3;
    private static final long RETRY_BASE_MS = 200;
    private static final long SEND_TIMEOUT_SEC = 10;

    private final KafkaTemplate<String, VideoEvent> kafkaTemplate;

    public void publish(String topic, Long videoId) {
        VideoEvent event = new VideoEvent(videoId);
        int attempt = 0;
        while (true) {
            try {
                kafkaTemplate.send(topic, String.valueOf(videoId), event)
                        .get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);
                log.info("[event] publish acked topic={} videoId={}", topic, videoId);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException("事件投递重试被中断 topic=" + topic + " videoId=" + videoId);
            } catch (Exception e) {
                if (++attempt >= MAX_RETRY) {
                    log.error("[event] 投递失败 topic={} videoId={} 已重试{}次: {}", topic, videoId, MAX_RETRY, e.getMessage());
                    throw new BusinessException("事件投递失败 topic=" + topic + " videoId=" + videoId);
                }
                long backoff = RETRY_BASE_MS * attempt;
                log.warn("[event] 投递失败，第{}次重试 ({}ms): {}", attempt, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException se) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("事件投递重试被中断 topic=" + topic + " videoId=" + videoId);
                }
            }
        }
    }
}
