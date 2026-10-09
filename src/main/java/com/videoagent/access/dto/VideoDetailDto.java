package com.videoagent.access.dto;

import java.util.List;

/**
 * 视频详情：元数据 + 转写分段 + 关键帧（供前端时间轴/问答展示）。
 */
public record VideoDetailDto(Long id, String title, String filename, String videoUrl, Integer durationSec,
                             String status, List<SegmentDto> segments, List<FrameDto> frames) {

    public record SegmentDto(Integer startSec, Integer endSec, String text) {
    }

    public record FrameDto(Integer frameTime, String imageUrl, String description) {
    }
}
