package com.videoagent.business.video;

import com.videoagent.access.auth.UserContext;
import com.videoagent.access.dto.UploadResponse;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.config.StorageProperties;
import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.event.Topics;
import com.videoagent.middleware.task.TaskService;
import com.videoagent.middleware.util.FfmpegCommandRunner;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 视频上传服务：落盘 → 元数据入库 → 触发转写事件。
 * 多租户：属主 user_id 记录到 video；损坏/超限文件在入库失败时删除已落盘文件，避免孤儿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoUploadService {

    private static final Set<String> ALLOWED_EXT = Set.of("mp4", "mov", "avi", "mkv", "webm", "flv", "m4a", "mp3",
            "wav", "aac", "flac");

    /** 上传时长上限（秒）：超过视为超大文件拒绝，避免极端资源占用 */
    private static final int MAX_DURATION_SEC = 3 * 60 * 60;

    private final StorageProperties storage;
    private final VideoMapper videoMapper;
    private final TaskService taskService;
    private final EventPublisher eventPublisher;
    private final FfmpegCommandRunner ffmpegRunner;

    public UploadResponse upload(MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        String original = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!ALLOWED_EXT.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("不支持的文件类型: ." + ext);
        }
        Long uid = UserContext.get() == null ? 0L : UserContext.get();

        Path target = null;
        try {
            Path dir = Paths.get(storage.videosDir());
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID() + "." + ext;
            target = dir.resolve(storedName);
            file.transferTo(target);

            int duration = ffmpegRunner.probeDurationSeconds(target);
            if (duration <= 0) {
                throw new BusinessException("无法识别媒体流，文件可能已损坏");
            }
            if (duration > MAX_DURATION_SEC) {
                throw new BusinessException("文件时长超过上限(" + (MAX_DURATION_SEC / 3600) + "小时)，请拆分后上传");
            }

            Video video = new Video();
            video.setTitle(title == null || title.isBlank() ? original : title);
            video.setFilename(storedName);
            video.setFilePath(target.toString());
            video.setDurationSec(duration);
            video.setUserId(uid);
            video.setStatus(VideoStatus.UPLOADED.name());
            videoMapper.insert(video);

            taskService.createTask(video.getId());

            // 触发离线流水线（异步）
            eventPublisher.publish(Topics.VIDEO_UPLOADED, video.getId());

            log.info("[upload] videoId={} owner={} title={} duration={}s", video.getId(), uid,
                    video.getTitle(), duration);
            return new UploadResponse(video.getId(), video.getId(), VideoStatus.UPLOADED.name());
        } catch (IOException e) {
            deleteQuietly(target);
            throw new BusinessException("文件保存失败: " + e.getMessage());
        } catch (BusinessException e) {
            // 探流/超限/入库失败：删除已落盘文件，避免孤儿文件不归库
            deleteQuietly(target);
            throw e;
        } catch (Exception e) {
            deleteQuietly(target);
            throw new BusinessException("上传处理失败: " + e.getMessage());
        }
    }

    private void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ex) {
                log.warn("[upload] 清理残留文件失败: {}", p);
            }
        }
    }

    private String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "mp4" : filename.substring(idx + 1);
    }
}
