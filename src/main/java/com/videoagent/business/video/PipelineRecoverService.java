package com.videoagent.business.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.event.Topics;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.middleware.metrics.PipelineMetricsService;
import com.videoagent.middleware.rag.IndexingService;
import com.videoagent.middleware.rag.KeywordBackfillService;
import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.CleanupStatus;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.TaskRecordMapper;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 进程崩溃恢复（启动时执行）：
 * 若上轮 JVM 在某个阶段中途崩溃，视频会永久停在 TRANSCRIBING/FRAMING/EMBEDDING 中间态——
 * {@link VideoPipelineService#shouldRun} 幂等守卫会挡住重放，而手动 retry 又仅对 FAILED 开放，导致视频"卡死"。
 * 本组件在应用启动就绪后把这些遗留状态收敛成可恢复状态：
 *  - TRANSCRIBING / FRAMING：标记为 FAILED（可在上传页点"重试"重新从该阶段跑）
 *  - EMBEDDING：向量已写入，仅差 READY 落库，直接补发 VIDEO_READY 让流水线收尾（避免重复建向量）
 *  - cleanup_status=PENDING：上一轮物理清理被中断，续跑后台清理（软删可自愈）
 *  已软删视频不参与流水线恢复（删除进行中，勿重放事件）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRecoverService implements ApplicationRunner {

    private static final String RECOVER_MSG = "服务重启导致处理中断，请点\"重试\"继续";

    private final VideoMapper videoMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final EventPublisher eventPublisher;
    private final CleanupService cleanupService;
    private final KeywordBackfillService keywordBackfillService;
    private final IndexingService indexingService;
    private final PipelineLockService pipelineLock;
    private final PipelineMetricsService metrics;
    @Qualifier("pipelineExecutor")
    private final ExecutorService pipelineExecutor;

    @Override
    public void run(ApplicationArguments args) {
        try {
            recoverStalledVideos();
        } catch (Exception e) {
            log.error("[recover] 启动恢复扫描失败", e);
        }
        try {
            resumePendingCleanups();
        } catch (Exception e) {
            log.error("[recover] 启动清理恢复扫描失败", e);
        }
        // 存量视频关键词回填：异步执行，不阻塞启动；幂等，可重复触发
        try {
            pipelineExecutor.submit(() -> {
                try {
                    keywordBackfillService.backfill();
                } catch (Exception e) {
                    log.error("[recover] 关键词存量回填失败", e);
                }
            });
        } catch (Exception e) {
            log.warn("[recover] 提交关键词回填任务失败: {}", e.getMessage());
        }
    }

    void recoverStalledVideos() {
        List<Video> stalled = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .in(Video::getStatus, VideoStatus.TRANSCRIBING.name(),
                        VideoStatus.FRAMING.name(), VideoStatus.EMBEDDING.name())
                .isNull(Video::getDeletedAt));
        if (stalled.isEmpty()) {
            log.info("[recover] 无遗留中间态视频");
            return;
        }
        for (Video video : stalled) {
            Long videoId = video.getId();
            String status = video.getStatus();
            if (VideoStatus.EMBEDDING.name().equals(status)) {
                // 崩溃可能发生在向量化中途（部分批次已入列、未到 READY）：先幂等续跑补齐剩余批次，再补发 READY 收尾。
                // 在分布式锁内执行并重校验状态，避免与手动 retry/多副本并发导致重复续跑；READY 在锁释放后再补发，
                // 防止 waitMs=0 的 READY 消费者持锁期间抢锁被跳过。
                log.warn("[recover] videoId={} 处于 EMBEDDING，续跑向量/倒排后补发 VIDEO_READY 收尾", videoId);
                Long vid = videoId;
                pipelineExecutor.submit(() -> {
                    // 与正常流水线统一用 "FRAMED" 聚合该阶段耗时（@see VideoPipelineService.Stage.FRAMED），
                    // 使恢复路径与正常路径的 EMBEDDING 耗时可合并在同一指标 key 下统计。
                    metrics.stageStart("FRAMED", vid);
                    final long[] bodyMs = {0};
                    boolean done = pipelineLock.tryRun("video:" + vid, 5000, () -> {
                        Video cur = videoMapper.selectById(vid);
                        if (cur == null || !VideoStatus.EMBEDDING.name().equals(cur.getStatus())) {
                            log.info("[recover] videoId={} 状态已变化或已处理，跳过续跑", vid);
                            return;
                        }
                        long t0 = System.nanoTime();
                        indexingService.process(vid);
                        bodyMs[0] = (System.nanoTime() - t0) / 1_000_000L;
                    });
                    if (bodyMs[0] > 0) {
                        metrics.stageEnd("FRAMED", vid, bodyMs[0]);
                    } else {
                        metrics.stageEnd("FRAMED", vid, 0);
                    }
                    if (done) {
                        eventPublisher.publish(Topics.VIDEO_READY, vid);
                    } else {
                        log.warn("[recover] videoId={} 未获取恢复锁，跳过本次续跑", vid);
                    }
                });
                continue;
            }
            // TRANSCRIBING / FRAMING：标记为 FAILED，交给用户"重试"
            video.setStatus(VideoStatus.FAILED.name());
            videoMapper.updateById(video);
            markTaskFailedIfRunning(videoId, status);
            log.warn("[recover] videoId={} 崩溃遗留中间态 {}，已置 FAILED 待重试", videoId, status);
        }
    }

    /** 续清上一轮遗漏的待清理视频（cleanup_status=PENDING） */
    void resumePendingCleanups() {
        List<Video> pending = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .eq(Video::getCleanupStatus, CleanupStatus.PENDING.name()));
        if (pending.isEmpty()) {
            log.info("[recover] 无待清理视频");
            return;
        }
        for (Video video : pending) {
            log.warn("[recover] videoId={} 续跑后台清理", video.getId());
            cleanupService.doClean(video.getId());
        }
    }

    private void markTaskFailedIfRunning(Long videoId, String stage) {
        TaskRecord task = taskRecordMapper.selectOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getVideoId, videoId).last("limit 1"));
        if (task == null || !"RUNNING".equals(task.getStatus())) {
            return;
        }
        task.setStage(stage);
        task.setStatus("FAILED");
        task.setErrorMsg(RECOVER_MSG);
        taskRecordMapper.updateById(task);
    }
}