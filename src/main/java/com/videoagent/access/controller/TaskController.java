package com.videoagent.access.controller;

import com.videoagent.access.dto.ApiResponse;
import com.videoagent.access.dto.TaskProgressDto;
import com.videoagent.business.video.VideoAccessService;
import com.videoagent.middleware.task.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务进度查询与手动重试（可观测性/降级展示）。
 */
@Tag(name = "任务进度")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final VideoAccessService accessService;

    @Operation(summary = "查询任务进度（状态/阶段耗时/错误）")
    @GetMapping("/video/{videoId}")
    public ApiResponse<TaskProgressDto> progress(@PathVariable Long videoId) {
        accessService.owned(videoId);
        return ApiResponse.ok(taskService.progress(videoId));
    }

    @Operation(summary = "手动重试失败任务（重投对应阶段事件）")
    @PostMapping("/video/{videoId}/retry")
    public ApiResponse<Void> retry(@PathVariable Long videoId) {
        accessService.owned(videoId);
        taskService.retry(videoId);
        return ApiResponse.ok();
    }
}
