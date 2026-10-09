package com.videoagent.business.video;

import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.CleanupStatus;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 视频删除（软删 + 后台清理）：
 * 前台立即不可见——置 deleted_at + cleanup_status=PENDING；
 * 实际物理清理（子表/向量/磁盘/视频行）异步交给 {@link CleanupService}，中断场景由启动恢复续清。
 * 分布式锁防并发重复软删/双清。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoDeletionService {

    private final VideoMapper videoMapper;
    private final PipelineLockService pipelineLock;
    private final CleanupService cleanupService;

    /** 软删视频：幂等；已软删则仅补发清理任务 */
    public void deleteVideo(Long id) {
        boolean locked = pipelineLock.tryRun("video:" + id, 5000, () -> {
            Video video = videoMapper.selectById(id);
            if (video == null) {
                throw new BusinessException("视频不存在: " + id);
            }
            boolean alreadySoftDeleted = video.getDeletedAt() != null;
            if (!alreadySoftDeleted) {
                video.setDeletedAt(LocalDateTime.now());
                video.setCleanupStatus(CleanupStatus.PENDING.name());
                videoMapper.updateById(video);
                log.info("[delete] videoId={} 已软删，待后台清理", id);
            }
            cleanupService.requestCleanup(id);
        });
        if (!locked) {
            throw new BusinessException("系统繁忙，删除稍后重试");
        }
    }
}