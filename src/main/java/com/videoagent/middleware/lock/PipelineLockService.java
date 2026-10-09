package com.videoagent.middleware.lock;

import com.videoagent.middleware.metrics.PipelineMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 分布式锁封装。
 * 作用：并发/重复消费控制 —— 同一 video 的多个事件到达时，只有持锁且状态匹配者真正执行，其余跳过。
 * 语义：拿不到锁直接跳过（幂等友好）；retry/delete 等需要"必须执行"的调用方可通过 waitMs 等待。
 */
@Slf4j
@Service
public class PipelineLockService {

    private static final String KEY_PREFIX = "video-agent:lock:";

    private final RedissonClient redisson;
    private final long defaultWaitMs;
    private final PipelineMetricsService metrics;

    public PipelineLockService(RedissonClient redisson,
                               @Value("${video-agent.lock.wait-ms:0}") long defaultWaitMs,
                               PipelineMetricsService metrics) {
        this.redisson = redisson;
        this.defaultWaitMs = defaultWaitMs;
        this.metrics = metrics;
    }

    /** 在给定 key 上持锁执行 action；拿不到锁则跳过并返回 false（用于幂等/防重）。 */
    public boolean tryRun(String key, Runnable action) {
        return tryRun(key, defaultWaitMs, action);
    }

    /** 指定等待时长持锁执行；拿不到锁返回 false，由调用方决定（重试/删除时可抛繁忙）。 */
    public boolean tryRun(String key, long waitMs, Runnable action) {
        RLock lock = redisson.getLock(KEY_PREFIX + key);
        boolean acquired = false;
        long waitNanos = System.nanoTime();
        try {
            // 看门狗模式：不传 leaseTime，由 Redisson 按 lockWatchdogTimeout/3 周期自动续期，
            // 长耗时阶段（转写/抽帧数分钟）持锁不过期；进程异常退出后看门狗停止续期，锁约 watchdog 超时后自动释放。
            acquired = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
            metrics.recordLock(acquired, (System.nanoTime() - waitNanos) / 1_000_000L);
            if (!acquired) {
                log.warn("[lock] 未获取到锁 key={} wait={}ms，跳过执行", key, waitMs);
                return false;
            }
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("[lock] 释放锁异常 key={} err={}", key, e.getMessage());
                }
            }
        }
    }
}