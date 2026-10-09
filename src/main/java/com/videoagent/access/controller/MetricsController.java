package com.videoagent.access.controller;

import com.videoagent.middleware.metrics.PipelineMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 运行态观测端点（需登录态）。
 * 聚合流水线阶段耗时、分布式锁竞争、流水线线程池队列深度、Kafka 消费者积压。
 * 指标均为进程内内存计数，重启即清零，用于运行态吞吐/积压/锁竞争的快速排查，不替代完整监控体系。
 */
@Tag(name = "监控")
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MetricsController {

    private final PipelineMetricsService metrics;
    @Qualifier("pipelineExecutor")
    private final ExecutorService pipelineExecutor;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    @Operation(summary = "流水线运行态指标")
    @GetMapping("/pipeline")
    public Map<String, Object> pipeline() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", metrics.stageStats());
        out.put("locks", metrics.lockStats());
        out.put("recent", metrics.recentEvents());
        out.put("pool", poolStats());
        out.put("kafkaConsumers", kafkaLag());
        return out;
    }

    private Map<String, Number> poolStats() {
        Map<String, Number> m = new LinkedHashMap<>();
        if (pipelineExecutor instanceof ThreadPoolExecutor tpe) {
            m.put("active", tpe.getActiveCount());
            m.put("poolSize", tpe.getPoolSize());
            m.put("maxPoolSize", tpe.getMaximumPoolSize());
            m.put("queueSize", tpe.getQueue().size());
            m.put("queueCapacity", tpe.getQueue().remainingCapacity() + tpe.getQueue().size());
            m.put("completedTasks", tpe.getCompletedTaskCount());
        } else {
            m.put("poolSize", -1);
        }
        return m;
    }

    /** 各 Kafka 消费者容器的 records-lag-max（尽力而为，失败不阻断主指标） */
    private List<Map<String, Object>> kafkaLag() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (MessageListenerContainer c : listenerRegistry.getListenerContainers()) {
                var cMetrics = c.metrics();
                long lagMax = 0;
                if (cMetrics != null) {
                    for (var group : cMetrics.values()) {
                        for (var e : group.entrySet()) {
                            if (e.getKey().name().contains("records-lag-max")) {
                                Number v = (Number) e.getValue().metricValue();
                                lagMax = Math.max(lagMax, v.longValue());
                            }
                        }
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("listener", c.getListenerId());
                row.put("recordsLagMax", lagMax);
                list.add(row);
            }
        } catch (Exception e) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("error", "metrics-unavailable: " + e.getClass().getSimpleName());
            list.add(row);
        }
        return list;
    }
}