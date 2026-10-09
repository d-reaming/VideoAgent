package com.videoagent.access.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务进度（可观测性核心 DTO）。
 * degrade: ASR 降级标记（null 表示本地通道；"cloud" 表示已降级云端）。
 */
public record TaskProgressDto(Long id, Long videoId, String stage, String status, Integer retryCount,
                              String errorMsg, Map<String, Long> phaseDurations,
                              String degrade, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
