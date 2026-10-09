package com.videoagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 相关 Bean 装配：ChatModel（DeepSeek）+ 帧描述并发线程池。
 */
@Configuration
public class AgentConfig {

    @Bean
    public ChatModel chatModel(LlmProperties llm) {
        return OpenAiChatModel.builder()
                .baseUrl(llm.deepseek().baseUrl())
                .apiKey(llm.deepseek().apiKey())
                .modelName(llm.deepseek().model())
                .temperature(llm.deepseek().temperature())
                .build();
    }

    /**
     * Qwen-VL 帧描述并发线程池。
     * concurrency <= 1 退化为串行（保留原行为）；否则用固定线程池并行调用视觉模型。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService frameDescribeExecutor(
            @Value("${video-agent.pipeline.frame-concurrency:5}") int concurrency) {
        int n = Math.max(concurrency, 1);
        if (n <= 1) {
            // 单线程固定池等价于串行，且支持统一关闭
            return Executors.newFixedThreadPool(1);
        }
        return Executors.newFixedThreadPool(n);
    }

    /**
     * 流水线工作线程池：Kafka 消费者只做事件派发，转写/抽帧/向量化等重活在此异步执行。
     * CallerRunsPolicy：队列满时退回调用线程执行，保证不丢任务。
     */
    @Bean(name = "pipelineExecutor", destroyMethod = "shutdown")
    public ExecutorService pipelineExecutor(
            @Value("${video-agent.pipeline.pool.core-size:4}") int core,
            @Value("${video-agent.pipeline.pool.max-size:8}") int max,
            @Value("${video-agent.pipeline.pool.queue-capacity:200}") int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(core, 1), Math.max(max, Math.max(core, 1)), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(queueCapacity, 1)),
                r -> new Thread(r, "pipeline-" + r.hashCode()),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
