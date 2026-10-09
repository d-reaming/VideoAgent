package com.videoagent.access.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.access.dto.ApiResponse;
import com.videoagent.access.dto.VideoBriefDto;
import com.videoagent.access.dto.VideoDetailDto;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.business.video.VideoAccessService;
import com.videoagent.business.video.VideoDeletionService;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 视频元数据查询接口。
 */
@Tag(name = "视频查询")
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoMapper videoMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FrameRecordMapper frameMapper;
    private final VideoDeletionService deletionService;
    private final VideoAccessService accessService;

    @Operation(summary = "视频列表（仅当前用户可见、未删除的）")
    @GetMapping
    public ApiResponse<List<VideoBriefDto>> list() {
        Long uid = accessService.requireUid();
        List<Video> videos = videoMapper.selectList(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getUserId, uid)
                        .isNull(Video::getDeletedAt)
                        .orderByDesc(Video::getCreatedAt));
        return ApiResponse.ok(videos.stream()
                .map(v -> new VideoBriefDto(v.getId(), v.getTitle(), v.getFilename(), v.getDurationSec(),
                        v.getStatus(), v.getCreatedAt()))
                .collect(Collectors.toList()));
    }

    @Operation(summary = "视频详情（含转写分段与关键帧）")
    @GetMapping("/{id}")
    public ApiResponse<VideoDetailDto> detail(@PathVariable Long id) {
        Video video = accessService.owned(id);
        List<TranscriptSegment> segments = segmentMapper.selectList(
                new LambdaQueryWrapper<TranscriptSegment>()
                        .eq(TranscriptSegment::getVideoId, id)
                        .orderByAsc(TranscriptSegment::getSeq));
        List<FrameRecord> frames = frameMapper.selectList(
                new LambdaQueryWrapper<FrameRecord>()
                        .eq(FrameRecord::getVideoId, id)
                        .orderByAsc(FrameRecord::getFrameTime));

        List<VideoDetailDto.SegmentDto> segDtos = segments.stream()
                .map(s -> new VideoDetailDto.SegmentDto(s.getStartSec(), s.getEndSec(), s.getText()))
                .collect(Collectors.toList());
        List<VideoDetailDto.FrameDto> frameDtos = frames.stream()
                .map(f -> new VideoDetailDto.FrameDto(f.getFrameTime(),
                        "/files/frames/" + Paths.get(f.getImagePath()).getFileName(),
                        f.getDescription()))
                .collect(Collectors.toList());

        return ApiResponse.ok(new VideoDetailDto(video.getId(), video.getTitle(), video.getFilename(),
                "/files/videos/" + video.getFilename(), video.getDurationSec(), video.getStatus(),
                segDtos, frameDtos));
    }

    @Operation(summary = "删除视频（连带清理关联数据与磁盘产物）")
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        accessService.owned(id);
        deletionService.deleteVideo(id);
        return ApiResponse.ok(true);
    }

    @Operation(summary = "重命名视频标题")
    @PutMapping("/{id}/title")
    public ApiResponse<Void> renameTitle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) {
            throw new BusinessException("标题不能为空");
        }
        Video video = accessService.owned(id);
        video.setTitle(title.trim());
        videoMapper.updateById(video);
        return ApiResponse.ok();
    }
}
