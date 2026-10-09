package com.videoagent.access.dto;

import java.util.List;

/**
 * 问答响应：回答 + 带时间戳引用（亮点 A，驱动前端跳转与溯源）。
 */
public record QaResponse(Long sessionId, Long videoId, String channel, String answer,
                         List<ReferenceDto> references) {

    public record ReferenceDto(Long videoId, Integer startSec, Integer endSec, String type, String quote) {
    }
}
