package com.videoagent.access.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话消息展示 DTO。
 */
public record ChatMessageDto(Long id, String role, String content, List<QaResponse.ReferenceDto> refs,
                             LocalDateTime createdAt) {
}
