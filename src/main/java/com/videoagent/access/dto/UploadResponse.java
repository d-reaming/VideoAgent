package com.videoagent.access.dto;

/**
 * 上传响应。
 */
public record UploadResponse(Long videoId, Long taskId, String status) {
}
