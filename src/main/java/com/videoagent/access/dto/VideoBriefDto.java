package com.videoagent.access.dto;

import java.time.LocalDateTime;

/**
 * 视频列表项。
 */
public record VideoBriefDto(Long id, String title, String filename, Integer durationSec, String status,
                            LocalDateTime createdAt) {
}
