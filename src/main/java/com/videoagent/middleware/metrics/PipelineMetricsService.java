package com.videoagent.middleware.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量流水线观测指标（内存、非侵入、无外部依赖；重启即清零，仅用于运行态排查/吞吐观察）。
 * 聚合：阶段耗时（count/avg/min/max）、在途阶段数、分布式锁获取/竞争/等待、最近阶段内最近事件。
 */
@Service
public class PipelineMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PipelineMetricsService.class);
    private static final int RECENT_LIMIT = 200;

    private final Map<String, StageStat> stages = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> inFlight = new ConcurrentHashMap<>();

    private final AtomicLong lockAttempts = new AtomicLong();
    private final AtomicLong lockAcquired = new AtomicLong();
    private final AtomicLong lockMissed = new AtomicLong();
    private final AtomicLong lockContended = new AtomicLong();
    private final AtomicLong lockTotalWaitMs = new AtomicLong();

    private final Deque<String> recent = new ArrayDeque<>();

    // ---------- 阶段 ----------

    public void stageStart(String stage, Long videoId) {
        inFlight.computeIfAbsent(stage, k -> new AtomicLong()).incrementAndGet();
    }

    public void stageEnd(String stage, Long videoId, long elapsedMs) {
        AtomicLong in = inFlight.computeIfAbsent(stage, k -> new AtomicLong());
        in.decrementAndGet();
        stages.computeIfAbsent(stage, k -> new StageStat()).record(elapsedMs);
        recordRecent(stage + "#" + videoId + "=" + elapsedMs + "ms");
    }

    public Map<String, Map<String, Number>> stageStats() {
        Map<String, Map<String, Number>> out = new LinkedHashMap<>();
        for (Map.Entry<String, StageStat> e : stages.entrySet()) {
            out.put(e.getKey(), e.getValue().toMap(
                    inFlight.computeIfAbsent(e.getKey(), k -> new AtomicLong()).get()));
        }
        return out;
    }

    // ---------- 锁 ----------

    public void recordLock(boolean acquired, long waitMs) {
        lockAttempts.incrementAndGet();
        lockTotalWaitMs.addAndGet(waitMs);
        if (waitMs > 0) {
            lockContended.incrementAndGet();
        }
        if (acquired) {
            lockAcquired.incrementAndGet();
        } else {
            lockMissed.incrementAndGet();
        }
    }

    public Map<String, Number> lockStats() {
        Map<String, Number> m = new LinkedHashMap<>();
        long attempts = lockAttempts.get();
        m.put("attempts", attempts);
        m.put("acquired", lockAcquired.get());
        m.put("missed", lockMissed.get());
        m.put("contended", lockContended.get());
        m.put("totalWaitMs", lockTotalWaitMs.get());
        m.put("avgWaitMs", attempts == 0 ? 0 : (double) lockTotalWaitMs.get() / attempts);
        return m;
    }

    // ---------- 最近事件 ----------

    private void recordRecent(String event) {
        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > RECENT_LIMIT) {
                recent.removeFirst();
            }
        }
    }

    public java.util.List<String> recentEvents() {
        synchronized (recent) {
            return new java.util.ArrayList<>(recent);
        }
    }

    // ---------- 阶段统计聚合 ----------

    private static final class StageStat {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalMs = new AtomicLong();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLong minMs = new AtomicLong(Long.MAX_VALUE);

        void record(long ms) {
            count.incrementAndGet();
            totalMs.addAndGet(ms);
            maxMs.updateAndGet(cur -> Math.max(cur, ms));
            minMs.updateAndGet(cur -> Math.min(cur, ms));
        }

        Map<String, Number> toMap(long inflight) {
            Map<String, Number> m = new LinkedHashMap<>();
            long c = count.get();
            m.put("count", c);
            m.put("inFlight", inflight);
            m.put("totalMs", totalMs.get());
            m.put("avgMs", c == 0 ? 0 : (double) totalMs.get() / c);
            m.put("minMs", c == 0 ? 0 : minMs.get());
            m.put("maxMs", maxMs.get());
            return m;
        }
    }
}