package com.videoagent.middleware.util;

import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.FfmpegProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ffmpeg / ffprobe 命令行封装（ProcessBuilder）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegCommandRunner {

    private final FfmpegProperties ffmpeg;

    /** 执行 ffprobe 获取媒体时长（秒） */
    public int probeDurationSeconds(Path mediaPath) {
        List<String> cmd = List.of(ffmpeg.path().replace("ffmpeg", "ffprobe"),
                "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", mediaPath.toString());
        String out = run(cmd, 30);
        try {
            return (int) Math.round(Double.parseDouble(out.trim()));
        } catch (NumberFormatException e) {
            throw new BusinessException("无法解析媒体时长: " + out);
        }
    }

    /** 探测媒体是否含视频流（纯音频返回 false），用于决定是否执行抽帧/视觉描述 */
    public boolean hasVideoStream(Path mediaPath) {
        List<String> cmd = List.of(ffmpeg.path().replace("ffmpeg", "ffprobe"),
                "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", mediaPath.toString());
        String out = run(cmd, 30);
        return out != null && out.toLowerCase().contains("video");
    }

    /** 执行 ffmpeg 命令（命令模板由调用方组装），输出 stdout+stderr */
    public String runFfmpeg(List<String> args, long timeoutSeconds) {
        List<String> cmd = new java.util.ArrayList<>(List.of(ffmpeg.path()));
        cmd.addAll(args);
        return run(cmd, timeoutSeconds);
    }

    private String run(List<String> cmd, long timeoutSeconds) {
        log.info("[ffmpeg] exec: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            // 后台线程异步读取输出，避免输出量超过管道缓冲导致进程阻塞（与 waitFor 死锁）
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("命令执行超时(>" + timeoutSeconds + "s): " + String.join(" ", cmd));
            }
            String output = outputFuture.join();
            if (process.exitValue() != 0) {
                throw new BusinessException("命令执行失败(exit=" + process.exitValue() + "): "
                        + String.join(" ", cmd) + "\n" + truncate(output));
            }
            return output;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("命令执行异常: " + e.getMessage());
        }
    }

    private String readAll(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "读取输出失败: " + e.getMessage();
        }
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() > 2000 ? s.substring(0, 2000) : s);
    }
}
