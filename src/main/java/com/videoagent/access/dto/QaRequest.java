package com.videoagent.access.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 问答请求。
 */
public record QaRequest(@NotBlank(message = "问题不能为空") String question,
                        Long videoId,
                        Long sessionId) {
}
