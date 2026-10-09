package com.videoagent.business.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoagent.config.StorageProperties;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.storage.entity.ChatMessage;
import com.videoagent.storage.entity.ChatSession;
import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.VideoOcr;
import com.videoagent.storage.entity.enums.CleanupStatus;
import com.videoagent.storage.mapper.ChatMessageMapper;
import com.videoagent.storage.mapper.ChatSessionMapper;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TaskRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoMapper;
import com.videoagent.storage.mapper.VideoOcrMapper;
import com.videoagent.storage.repository.KeywordRepository;
import com.videoagent.storage.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * 后台物理清理服务：软删后或启动恢复时调用，幂等地清理某视频的全部关联产物。
 * 清理顺序：磁盘路径定格 → MySQL 子表 → PG 向量 → Redis 缓存 → 磁盘文件 → 物理删除 video 行。
 * 与 DV/恢复共用同 key 分布式锁，避免并发双清。中途异常抛错，由恢复线程按 PENDING 续清，可自愈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService {

    private final VideoMapper videoMapper;
    private final TaskRecordMapper taskRecordMapper;
    private final TranscriptSegmentMapper segmentMapper;
    private final FrameRecordMapper frameMapper;
    private final VideoOcrMapper videoOcrMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final VectorRepository vectorRepository;
    private final KeywordRepository keywordRepository;
    private final PipelineLockService pipelineLock;
    private final StringRedisTemplate redisTemplate;
    private final StorageProperties storage;
    @Qualifier("pipelineExecutor")
    private final ExecutorService pipelineExecutor;

    /** 提交异步清理（软删标记后调用）；提交失败不抛出，交由启动恢复兜底 */
    public void requestCleanup(Long videoId) {
        try {
            pipelineExecutor.submit(() -> doClean(videoId));
        } catch (Exception e) {
            log.warn("[cleanup] videoId={} 提交清理任务失败，交由启动恢复兜底: {}", videoId, e.getMessage());
        }
    }

    /**
     * 幂等物理清理：仅处理 cleanup_status=PENDING 的视频；
     * 已完成( DONE)、未标记( NONE)、仍可见(未软删或已恢复) 均跳过，视为可重入。
     */
    public void doClean(Long videoId) {
        pipelineLock.tryRun("video:" + videoId, 5000, () -> doCleanUnderLock(videoId));
    }

    private void doCleanUnderLock(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            log.info("[cleanup] videoId={} 已不存在，跳过", videoId);
            return;
        }
        // 幂等守卫：仅清理处于 PENDING 的软删视频；其余状态不动（防止误清恢复中的视频）
        if (!CleanupStatus.PENDING.name().equals(video.getCleanupStatus())
                || video.getDeletedAt() == null) {
            log.info("[cleanup] videoId={} status={} deletedAt={} 非待清理状态，跳过",
                    videoId, video.getCleanupStatus(), video.getDeletedAt());
            return;
        }

        try {
            // ① 定格磁盘产物路径（删 DB 行前收集）
            List<Path> filesToDelete = new ArrayList<>();
            if (video.getFilePath() != null && !video.getFilePath().isBlank()) {
                filesToDelete.add(Paths.get(video.getFilePath()));
            }
            filesToDelete.add(Paths.get(storage.audioDir(), videoId + ".wav"));
            List<FrameRecord> frames = frameMapper.selectList(
                    new LambdaQueryWrapper<FrameRecord>().eq(FrameRecord::getVideoId, videoId));
            for (FrameRecord f : frames) {
                if (f.getImagePath() != null && !f.getImagePath().isBlank()) {
                    filesToDelete.add(Paths.get(f.getImagePath()));
                }
            }
            Path framesDir = Paths.get(storage.framesDir(), String.valueOf(videoId));

            // ② MySQL 子表：逐表独立，某表无数据不中断；先收集 session id 供随后清 Redis 缓存
            List<Long> sessionIds = new ArrayList<>();
            List<ChatSession> sessions = chatSessionMapper.selectList(
                    new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getVideoId, videoId));
            for (ChatSession s : sessions) {
                sessionIds.add(s.getId());
                chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, s.getId()));
            }
            chatSessionMapper.delete(new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getVideoId, videoId));
            taskRecordMapper.delete(new LambdaQueryWrapper<TaskRecord>().eq(TaskRecord::getVideoId, videoId));
            segmentMapper.delete(new LambdaQueryWrapper<TranscriptSegment>().eq(TranscriptSegment::getVideoId, videoId));
            frameMapper.delete(new LambdaQueryWrapper<FrameRecord>().eq(FrameRecord::getVideoId, videoId));
            videoOcrMapper.delete(new LambdaQueryWrapper<VideoOcr>().eq(VideoOcr::getVideoId, videoId));

            // ③ PG 向量（独立数据源，失败仅记日志，不阻断剩余清理）
            try {
                vectorRepository.deleteByVideoId(videoId);
            } catch (Exception e) {
                log.warn("[cleanup] videoId={} 清向量失败: {}", videoId, e.getMessage());
            }
            // ③.1 关键词倒排表
            try {
                keywordRepository.deleteByVideoId(videoId);
            } catch (Exception e) {
                log.warn("[cleanup] videoId={} 清倒排失败: {}", videoId, e.getMessage());
            }

            // ④ Redis 状态/会话缓存：按真实 key 逐个删除（不依赖 glob 展开）
            try {
                List<String> keys = new ArrayList<>();
                keys.add("video-agent:pipeline:state:" + videoId);
                keys.add("video-agent:pipeline:start:" + videoId);
                keys.add("video-agent:degrade:" + videoId);
                keys.add("video-agent:session:list:" + videoId);
                for (Long sid : sessionIds) {
                    keys.add("video-agent:session:messages:" + sid);
                }
                redisTemplate.delete(keys);
            } catch (Exception e) {
                log.warn("[cleanup] videoId={} 清 Redis 失败: {}", videoId, e.getMessage());
            }

            // ⑤ 磁盘产物，失败仅记日志
            for (Path p : filesToDelete) {
                deleteQuietly(p);
            }
            deleteQuietlyRecursive(framesDir);

            // ⑥ 物理删除 video 行
            videoMapper.deleteById(videoId);
            log.info("[cleanup] videoId={} 清理完成", videoId);
        } catch (Exception e) {
            // 保持 PENDING，交由恢复线程续清
            log.warn("[cleanup] videoId={} 清理中断，保持 PENDING 待重试: {}", videoId, e.getMessage());
            throw e instanceof RuntimeException r ? r : new RuntimeException(e);
        }
    }

    private void deleteQuietly(Path p) {
        try {
            if (p != null) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.warn("[cleanup] 删除文件失败: {} err={}", p, e.getMessage());
        }
    }

    private void deleteQuietlyRecursive(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception e) {
                            log.warn("[cleanup] 删除文件失败: {} err={}", p, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("[cleanup] 清理目录失败: {} err={}", dir, e.getMessage());
        }
    }
}