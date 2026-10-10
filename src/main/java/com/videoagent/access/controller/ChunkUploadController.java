package com.videoagent.access.controller;

import com.videoagent.access.dto.ApiResponse;
import com.videoagent.access.dto.UploadResponse;
import com.videoagent.business.video.ChunkUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 分片上传 + 断点续传接口（WS4）。前端按流程调用 init → upload(逐片) → complete；可随时查 progress。
 */
@Tag(name = "分片上传")
@RestController
@RequestMapping("/api/videos/chunk")
@RequiredArgsConstructor
public class ChunkUploadController {

    private final ChunkUploadService chunkUploadService;

    @Operation(summary = "初始化分片上传，返回 uploadId")
    @PostMapping("/init")
    public ApiResponse<ChunkUploadService.ChunkInit> init(@RequestParam("filename") String filename,
                                                          @RequestParam("totalChunks") int totalChunks,
                                                          @RequestParam(value = "title", required = false) String title) {
        return ApiResponse.ok(chunkUploadService.init(filename, totalChunks, title));
    }

    @Operation(summary = "上传单个分片（≤5MB）")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> upload(@RequestParam("uploadId") String uploadId,
                                    @RequestParam("chunkIndex") int chunkIndex,
                                    @RequestParam("file") MultipartFile file) {
        chunkUploadService.upload(uploadId, chunkIndex, file);
        return ApiResponse.ok();
    }

    @Operation(summary = "合并分片并完成上传（触发处理流水线）")
    @PostMapping("/complete")
    public ApiResponse<UploadResponse> complete(@RequestParam("uploadId") String uploadId) {
        return ApiResponse.ok(chunkUploadService.complete(uploadId));
    }

    @Operation(summary = "查询断点续传进度")
    @GetMapping("/progress")
    public ApiResponse<ChunkUploadService.ChunkProgress> progress(@RequestParam("uploadId") String uploadId) {
        return ApiResponse.ok(chunkUploadService.progress(uploadId));
    }
}