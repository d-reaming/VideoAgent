package com.videoagent.access.controller;

import com.videoagent.access.dto.ApiResponse;
import com.videoagent.access.dto.UploadResponse;
import com.videoagent.business.video.VideoUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频上传接口。
 */
@Tag(name = "视频上传")
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class UploadController {

    private final VideoUploadService uploadService;

    @Operation(summary = "上传视频/音频（≤2GB），异步触发处理流水线")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "title", required = false) String title) {
        return ApiResponse.ok(uploadService.upload(file, title));
    }
}
