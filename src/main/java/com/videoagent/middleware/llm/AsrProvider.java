package com.videoagent.middleware.llm;

import java.nio.file.Path;
import java.util.List;

/**
 * ASR 可插拔接口：本地 faster-whisper 为主，云端兜底。
 */
public interface AsrProvider {

    String name();

    /**
     * 转写音频为带时间戳的分段。
     *
     * @param audioPath 音频文件（wav）
     * @param language  语言代码（如 zh / en）
     */
    List<Segment> transcribe(Path audioPath, String language);

    record Segment(int startSec, int endSec, String text) {
    }
}
