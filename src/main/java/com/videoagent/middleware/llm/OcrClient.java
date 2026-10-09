package com.videoagent.middleware.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.OcrProperties;
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
 * 本地 OCR：面向持久化 RapidOCR 常驻进程（stdin/stdout JSON-lines，与 whisper worker 同模式）。
 * 进程首启时加载一次模型，之后每帧只花推理时间，摊销模型冷加载成本。
 * 请求/响应在 ReentrantLock 下串行交换（OCR 推理较快，单 worker 串行可接受）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrClient {

    private final OcrProperties ocr;
    private final ObjectMapper objectMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private long requestId = 0;

    /** 一条 OCR 识别结果 */
    public record OcrItem(String text, double confidence) {
    }

    public boolean enabled() {
        OcrProperties.Local l = ocr.local();
        return l != null && Boolean.TRUE.equals(l.enabled());
    }

    /** 识别单张帧图上的文字；未启用时返回空列表 */
    public List<OcrItem> ocr(Path imagePath) {
        if (!enabled()) {
            return List.of();
        }
        lock.lock();
        try {
            ensureWorker();
            requestId++;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", requestId);
            req.put("image", imagePath.toAbsolutePath().toString());
            stdin.write(objectMapper.writeValueAsString(req) + "\n");
            stdin.flush();

            String line = stdout.readLine();
            if (line == null) {
                throw new BusinessException("本地 OCR worker 已退出");
            }
            JsonNode root = objectMapper.readTree(line);
            if (root.has("error")) {
                throw new BusinessException("本地 OCR 错误: " + root.get("error").asText());
            }
            List<OcrItem> items = new ArrayList<>();
            for (JsonNode n : root.get("items")) {
                items.add(new OcrItem(n.get("text").asText(),
                        n.has("confidence") ? n.get("confidence").asDouble() : 0.0));
            }
            return items;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("本地 OCR 异常: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** 懒启动持久化 worker；进程异常死亡时下次调用自动重建 */
    private void ensureWorker() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        OcrProperties.Local l = ocr.local();
        double minConf = l.minConfidence() == null ? 0.5 : l.minConfidence();
        List<String> cmd = List.of(l.python(), l.workerServerScript(),
                "--min-confidence", String.valueOf(minConf),
                "--device", l.device() == null || l.device().isBlank() ? "cpu" : l.device());
        log.info("[ocr:local] 持久化 worker 启动: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        // Windows 下 Python 管道 stdout 默认 GBK(cp936)，与 Java 的 UTF-8 读取不一致会导致
        // 日文等非 GBK 字符解码成乱码(U+FFFD)入库；强制 worker 输出 UTF-8 保证编码一致
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        // 持续排空 stderr，避免管道缓冲满导致 worker 阻塞写日志
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                r.lines().forEach(ln -> log.info("[ocr:wkr] {}", ln));
            } catch (Exception ignored) {
                // worker 正常退出时结束排空线程
            }
        }, "ocr-stderr-drain");
        drain.setDaemon(true);
        drain.start();
    }

    @PreDestroy
    public void shutdown() {
        lock.lock();
        try {
            if (process != null) {
                process.destroy();
                log.info("[ocr:local] 持久化 worker 已关闭");
            }
        } finally {
            lock.unlock();
        }
    }
}
