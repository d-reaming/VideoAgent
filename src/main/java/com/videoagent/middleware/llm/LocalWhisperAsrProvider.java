package com.videoagent.middleware.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.AsrProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地 ASR：面向持久化 faster-whisper 常驻进程（stdin/stdout JSON-lines）。
 * 进程首启时加载一次模型（约 43s），之后每次转写只花推理时间，摊销模型冷加载成本。
 * 请求/响应在 ReentrantLock 下串行交换（转写本就基本串行，可接受）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalWhisperAsrProvider implements AsrProvider {

    private final AsrProperties asr;
    private final ObjectMapper objectMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private long requestId = 0;

    @Override
    public String name() {
        return "local-faster-whisper";
    }

    @Override
    public List<Segment> transcribe(Path audioPath, String language) {
        lock.lock();
        try {
            ensureWorker();
            requestId++;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", requestId);
            req.put("audio", audioPath.toAbsolutePath().toString());
            req.put("language", language == null ? asr.local().language() : language);
            stdin.write(objectMapper.writeValueAsString(req) + "\n");
            stdin.flush();

            String line = stdout.readLine();
            if (line == null) {
                throw new BusinessException("本地转写 worker 已退出");
            }
            JsonNode root = objectMapper.readTree(line);
            if (root.has("error")) {
                throw new BusinessException("本地转写错误: " + root.get("error").asText());
            }
            List<Segment> segments = new ArrayList<>();
            for (JsonNode n : root.get("segments")) {
                segments.add(new Segment((int) Math.round(n.get("start").asDouble()),
                        (int) Math.round(n.get("end").asDouble()), n.get("text").asText()));
            }
            return segments;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("本地转写异常: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** 懒启动持久化 worker；进程异常死亡时下次调用自动重建 */
    private void ensureWorker() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        AsrProperties.Local local = asr.local();
        List<String> cmd = List.of(local.python(), local.workerServerScript(),
                "--model", local.modelSize(),
                "--device", local.device(),
                "--language", local.language() == null ? "zh" : local.language());
        log.info("[asr:local] 持久化 worker 启动: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        // 持续排空 stderr，避免管道缓冲满导致 worker 阻塞写日志
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(l -> log.info("[asr:wkr] {}", l));
            } catch (Exception ignored) {
                // worker 正常退出时结束排空线程
            }
        }, "whisper-stderr-drain");
        drain.setDaemon(true);
        drain.start();
    }

    @PreDestroy
    public void shutdown() {
        lock.lock();
        try {
            if (process != null) {
                process.destroy();
                log.info("[asr:local] 持久化 worker 已关闭");
            }
        } finally {
            lock.unlock();
        }
    }
}