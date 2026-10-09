package com.videoagent.business.video;

import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.event.Topics;
import com.videoagent.middleware.event.VideoEvent;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.middleware.metrics.PipelineMetricsService;
import com.videoagent.middleware.rag.IndexingService;
import com.videoagent.middleware.task.TaskService;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.TaskStage;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

/**
 * 视频处理流水线（状态机驱动，亮点 D）：
 * VIDEO_UPLOADED → 转写 → VIDEO_TRANSCRIBED → 抽帧 → VIDEO_FRAMED → 向量化 → VIDEO_READY
 *
 * 关键改造（精进）：
 * 1. Kafka 解耦：@KafkaListener 只做「事件派发」，把阶段执行 submit 到独立流水线线程池(pipelineExecutor)，
 *    立即返回，绝不阻塞 poll → 长任务不再依赖 max.poll.interval 兜底。
 * 2. 分布式锁 + 幂等守卫：阶段执行在 pipelineExecutor 中先抢 Redisson 分布式锁，锁内再校验
 *    shouldRun(期望状态)。同一 video 的并发/重复事件，只有持锁且状态匹配者真正执行，其余跳过 —— 严格防重。
 * 3. 每阶段失败 → task_record 记 FAILED + 重试次数（手动重投）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoPipelineService {

    private static final String LOCK_PREFIX = "video:";

    private final TaskService taskService;
    private final TranscriptionService transcriptionService;
    private final FrameExtractService frameExtractService;
    private final IndexingService indexingService;
    private final EventPublisher eventPublisher;
    private final VideoMapper videoMapper;
    private final PipelineLockService pipelineLock;
    private final PipelineMetricsService metrics;
    @Qualifier("pipelineExecutor")
    private final ExecutorService pipelineExecutor;

    /** 幂等守卫：当前视频状态必须等于期望状态才执行本阶段；否则视为重放/重复投递并跳过 */
    private boolean shouldRun(Long videoId, VideoStatus expectedStatus) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            log.warn("[pipeline] videoId={} 不存在，跳过阶段", videoId);
            return false;
        }
        boolean ok = expectedStatus.name().equals(video.getStatus());
        if (!ok) {
            log.info("[pipeline] 跳过重放视频 videoId={} 当前状态={}（期望={}）",
                    videoId, video.getStatus(), expectedStatus);
        }
        return ok;
    }

    @KafkaListener(topics = Topics.VIDEO_UPLOADED)
    public void onUploaded(VideoEvent event) {
        dispatch(Stage.UPLOADED, event);
    }

    @KafkaListener(topics = Topics.VIDEO_TRANSCRIBED)
    public void onTranscribed(VideoEvent event) {
        dispatch(Stage.TRANSCRIBED, event);
    }

    @KafkaListener(topics = Topics.VIDEO_FRAMED)
    public void onFramed(VideoEvent event) {
        dispatch(Stage.FRAMED, event);
    }

    @KafkaListener(topics = Topics.VIDEO_READY)
    public void onReady(VideoEvent event) {
        dispatch(Stage.READY, event);
    }

    /** 消费者线程：仅派发，立即返回，不阻塞 poll */
    private void dispatch(Stage stage, VideoEvent event) {
        pipelineExecutor.submit(() -> runStage(stage, event.videoId()));
    }

    /** 工作线程内：加锁 + 幂等守卫 + 阶段执行 + 落库；下一阶段事件在锁释放后发布，避免“持锁发布→下阶段线程抢锁被跳过” */
    private void runStage(Stage stage, Long videoId) {
        metrics.stageStart(stage.name(), videoId);
        String[] nextTopic = {null};
        final long[] bodyMs = {0};
        pipelineLock.tryRun(LOCK_PREFIX + videoId, () -> {
            if (!shouldRun(videoId, stage.expected)) {
                return;
            }
            long t0 = System.nanoTime();
            try {
                switch (stage) {
                    case UPLOADED -> {
                        taskService.stageStart(videoId, TaskStage.TRANSCRIBING, VideoStatus.TRANSCRIBING);
                        transcriptionService.process(videoId);
                        taskService.stageDone(videoId, "transcribe_ms");
                        nextTopic[0] = Topics.VIDEO_TRANSCRIBED;
                    }
                    case TRANSCRIBED -> {
                        taskService.stageStart(videoId, TaskStage.FRAMING, VideoStatus.FRAMING);
                        frameExtractService.process(videoId);
                        taskService.stageDone(videoId, "frame_ms");
                        nextTopic[0] = Topics.VIDEO_FRAMED;
                    }
                    case FRAMED -> {
                        taskService.stageStart(videoId, TaskStage.EMBEDDING, VideoStatus.EMBEDDING);
                        indexingService.process(videoId);
                        taskService.stageDone(videoId, "embed_ms");
                        nextTopic[0] = Topics.VIDEO_READY;
                    }
                    case READY -> {
                        taskService.complete(videoId);
                        log.info("[pipeline] videoId={} 处理完成(READY)", videoId);
                    }
                }
            } catch (Exception e) {
                taskService.fail(videoId, stage.failStage.name(), e.getMessage());
            } finally {
                bodyMs[0] = (System.nanoTime() - t0) / 1_000_000L;
            }
        });
        // 阶段体实际执行过（bodyMs>0）才结算耗时指标
        if (bodyMs[0] > 0) {
            metrics.stageEnd(stage.name(), videoId, bodyMs[0]);
        }
        // 锁释放后再投递下一阶段事件；投递失败则标记任务失败，交由手动 retry 补偿
        if (nextTopic[0] != null) {
            try {
                eventPublisher.publish(nextTopic[0], videoId);
            } catch (Exception e) {
                taskService.fail(videoId, stage.failStage.name(), "事件投递失败: " + e.getMessage());
            }
        }
    }

    private enum Stage {
        UPLOADED(VideoStatus.UPLOADED, TaskStage.TRANSCRIBING),
        TRANSCRIBED(VideoStatus.TRANSCRIBING, TaskStage.FRAMING),
        FRAMED(VideoStatus.FRAMING, TaskStage.EMBEDDING),
        READY(VideoStatus.EMBEDDING, TaskStage.EMBEDDING);

        final VideoStatus expected;
        final TaskStage failStage;

        Stage(VideoStatus expected, TaskStage failStage) {
            this.expected = expected;
            this.failStage = failStage;
        }
    }
}