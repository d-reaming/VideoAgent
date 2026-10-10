package com.videoagent.middleware.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动重试调度器：周期扫描 FAILED 任务，按退避到期复投对应阶段事件重跑；
 * 重试次数达到 {@link TaskService#AUTO_RETRY_MAX} 仍未成功则收编为死信 DEAD 并落账。
 *
 * <p>退避策略按已失败次数指数递增：1 次→30s、2 次→60s、≥3 次→120s（用 task_record.updated_at + 退避判断到期）。
 * 复用 {@link TaskService#retry} 的阶段→topic 映射与锁契约，与手动重试共用同一把分布式锁防并发重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRetryScheduler {

    private final TaskRecordMapper taskRecordMapper;
    private final TaskService taskService;

    /** 每个周期扫描一次 FAILED（带数量上限，防止极少数异常态拖慢调度线程） */
    @Scheduled(fixedDelayString = "${video-agent.task.retry.interval-ms:30000}")
    public void scanAndRetry() {
        List<TaskRecord> failed = taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getStatus, TaskRecord.STATUS_FAILED)
                .last("limit 500"));
        if (failed.isEmpty()) {
            return;
        }
        int retried = 0;
        for (TaskRecord task : failed) {
            Long videoId = task.getVideoId();
            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            // 防御性兜底：FAILED 但次数已达上限且未被 fail() 收编为 DEAD 的异常边缘状态，统一归为死信
            if (retryCount >= TaskService.AUTO_RETRY_MAX) {
                try {
                    taskService.markDead(videoId, "重试次数已达上限(" + TaskService.AUTO_RETRY_MAX + "次)");
                    log.warn("[retry] videoId={} FAILED 次数已达上限，收编为死信 DEAD", videoId);
                } catch (Exception e) {
                    log.warn("[retry] 死信收编失败 videoId={} err={}", videoId, e.getMessage());
                }
                continue;
            }
            if (!backoffDue(task, retryCount)) {
                continue;
            }
            try {
                taskService.retry(videoId);
                retried++;
                log.info("[retry] 自动重投 videoId={} 当前失败次数={}", videoId, retryCount);
            } catch (Exception e) {
                // 不满足重试条件（状态变化/锁繁忙/未知阶段）→ 跳过，留待下一周期
                log.warn("[retry] 自动重试跳过 videoId={} reason={}", videoId, e.getMessage());
            }
        }
        if (retried > 0) {
            log.info("[retry] 本周期共自动重投 {} 个任务", retried);
        }
    }

    /** 按已失败次数计算退避并判断是否到期（updated_at 为最近一次失败写入时间） */
    private boolean backoffDue(TaskRecord task, int retryCount) {
        LocalDateTime updated = task.getUpdatedAt();
        if (updated == null) {
            return true;
        }
        return !updated.plusSeconds(backoffSeconds(retryCount)).isAfter(LocalDateTime.now());
    }

    /** 退避秒数：1→30，2→60，≥3→120（<1 视为 0，立即重试） */
    static long backoffSeconds(int retryCount) {
        return switch (retryCount) {
            case 1 -> 30;
            case 2 -> 60;
            default -> retryCount <= 0 ? 0 : 120;
        };
    }
}