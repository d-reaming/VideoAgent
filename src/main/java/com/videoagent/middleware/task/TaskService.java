package com.videoagent.middleware.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.dto.TaskProgressDto;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.event.Topics;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.TaskStage;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.TaskRecordMapper;
import com.videoagent.storage.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 任务状态机服务：任务创建/阶段更新/耗时统计/失败重试。
 * 可观测性核心：阶段耗时、错误信息、重试次数都落在 task_record。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRecordMapper taskRecordMapper;
    private final VideoMapper videoMapper;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final PipelineLockService pipelineLock;

    /** 流水线状态缓存/阶段开始时间的 Redis key 前缀与 TTL（重启不丢，重启后可继续） */
    private static final String STATE_KEY = "video-agent:pipeline:state:";
    private static final String START_KEY = "video-agent:pipeline:start:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /** 新建任务（视频上传后创建） */
    public TaskRecord createTask(Long videoId) {
        TaskRecord task = new TaskRecord();
        task.setVideoId(videoId);
        task.setStage(TaskStage.TRANSCRIBING.name());
        task.setStatus("RUNNING");
        task.setRetryCount(0);
        task.setPhaseDurations("{}");
        taskRecordMapper.insert(task);
        return task;
    }

    /** 阶段开始：记录开始时间(Redis) + 更新视频/任务状态 */
    public void stageStart(Long videoId, TaskStage stage, VideoStatus videoStatus) {
        updateVideoStatus(videoId, videoStatus);
        TaskRecord task = getByVideoId(videoId);
        task.setStage(stage.name());
        task.setStatus("RUNNING");
        task.setErrorMsg(null);
        taskRecordMapper.updateById(task);
        // 阶段开始时间放 Redis，重启不丢
        redisTemplate.opsForValue().set(START_KEY + videoId, String.valueOf(System.currentTimeMillis()),
                CACHE_TTL.toSeconds(), TimeUnit.SECONDS);
        cacheState(task);
        log.info("[task] stage start videoId={} stage={}", videoId, stage);
    }

    /** 阶段完成：记录耗时到 phase_durations */
    public void stageDone(Long videoId, String durationKey) {
        Long start = readStartMillis(videoId);
        if (start == null) {
            return;
        }
        redisTemplate.delete(START_KEY + videoId);
        TaskRecord task = getByVideoId(videoId);
        Map<String, Long> durations = parseDurations(task.getPhaseDurations());
        durations.put(durationKey, System.currentTimeMillis() - start);
        task.setPhaseDurations(writeDurations(durations));
        taskRecordMapper.updateById(task);
        cacheState(task);
        log.info("[task] stage done videoId={} {}={}ms", videoId, durationKey,
                System.currentTimeMillis() - start);
    }

    private Long readStartMillis(Long videoId) {
        String v = redisTemplate.opsForValue().get(START_KEY + videoId);
        return v == null ? null : Long.parseLong(v);
    }

    /** 任务失败：记录错误 + 重试次数 + 视频 FAILED */
    public void fail(Long videoId, String stage, String errorMsg) {
        TaskRecord task = getByVideoId(videoId);
        task.setStage(stage);
        task.setStatus("FAILED");
        task.setErrorMsg(truncate(errorMsg));
        task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        taskRecordMapper.updateById(task);
        updateVideoStatus(videoId, VideoStatus.FAILED);
        cacheState(task);
        log.error("[task] FAILED videoId={} stage={} err={}", videoId, stage, errorMsg);
    }

    /** 全部完成：任务 DONE + 视频 READY */
    public void complete(Long videoId) {
        TaskRecord task = getByVideoId(videoId);
        task.setStatus("DONE");
        task.setStage(TaskStage.EMBEDDING.name());
        taskRecordMapper.updateById(task);
        updateVideoStatus(videoId, VideoStatus.READY);
        cacheState(task);
    }

    /** 手动重试：根据失败阶段重投对应事件（需分布式锁防并发重投） */
    public void retry(Long videoId) {
        // 锁内只做状态重置与读取；锁释放后再投递事件。
        // 否则 waitMs=0 的流水线消费者在重试持锁期间抢锁会拿不到并静默跳过，导致重投偶发被吞。
        String[] topic = {null};
        boolean locked = pipelineLock.tryRun("video:" + videoId, 5000, () -> {
            Video video = videoMapper.selectById(videoId);
            if (video == null) {
                throw new BusinessException("视频不存在");
            }
            if (!VideoStatus.FAILED.name().equals(video.getStatus())) {
                throw new BusinessException("仅 FAILED 状态可重试");
            }
            TaskRecord task = getByVideoId(videoId);
            String stage = task.getStage();
            // 允许重投的次数限制，防止无限重试
            if (task.getRetryCount() != null && task.getRetryCount() >= 3) {
                throw new BusinessException("重试次数已达上限(3次)");
            }
            // 失败后视频状态被置为 FAILED；重试前把它重置为「本阶段之前」的期望状态，
            // 否则 VideoPipelineService.shouldRun 幂等守卫会因状态≠期望而拦截掉这次重投
            VideoStatus expectedBefore = switch (stage) {
                case "TRANSCRIBING" -> VideoStatus.UPLOADED;
                case "FRAMING" -> VideoStatus.TRANSCRIBING;
                case "EMBEDDING" -> VideoStatus.FRAMING;
                default -> throw new BusinessException("未知阶段: " + stage);
            };
            updateVideoStatus(videoId, expectedBefore);
            topic[0] = switch (stage) {
                case "TRANSCRIBING" -> Topics.VIDEO_UPLOADED;
                case "FRAMING" -> Topics.VIDEO_TRANSCRIBED;
                case "EMBEDDING" -> Topics.VIDEO_FRAMED;
                default -> throw new BusinessException("未知阶段: " + stage);
            };
        });
        if (!locked) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }
        // 锁已释放后才投递，唤醒流水线消费重投事件
        eventPublisher.publish(topic[0], videoId);
    }

    /** 进度查询：优先读 Redis 状态缓存（高频读，快），未命中回源 task_record */
    public TaskProgressDto progress(Long videoId) {
        TaskRecord cached = readState(videoId);
        if (cached != null) {
            return toDto(cached, videoId);
        }
        return toDto(getByVideoId(videoId), videoId);
    }

    private TaskProgressDto toDto(TaskRecord task, Long videoId) {
        String degrade = redisTemplate.opsForValue().get("video-agent:degrade:" + videoId);
        return new TaskProgressDto(task.getId(), task.getVideoId(), task.getStage(), task.getStatus(),
                task.getRetryCount(), task.getErrorMsg(), parseDurations(task.getPhaseDurations()),
                degrade, task.getCreatedAt(), task.getUpdatedAt());
    }

    /** 写流水线状态到 Redis（TTL 1h，供高频进度查询与幂等快速判断） */
    private void cacheState(TaskRecord task) {
        try {
            redisTemplate.opsForValue().set(STATE_KEY + task.getVideoId(),
                    objectMapper.writeValueAsString(task), CACHE_TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[task] cacheState fail videoId={} err={}", task.getVideoId(), e.getMessage());
        }
    }

    /** 读 Redis 状态缓存，未命中返回 null */
    private TaskRecord readState(Long videoId) {
        try {
            String json = redisTemplate.opsForValue().get(STATE_KEY + videoId);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, TaskRecord.class);
        } catch (Exception e) {
            log.warn("[task] readState fail videoId={} err={}", videoId, e.getMessage());
            return null;
        }
    }

    public TaskRecord getByVideoId(Long videoId) {
        TaskRecord task = taskRecordMapper.selectOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getVideoId, videoId).last("limit 1"));
        if (task == null) {
            throw new BusinessException("任务不存在: videoId=" + videoId);
        }
        return task;
    }

    private void updateVideoStatus(Long videoId, VideoStatus status) {
        Video video = videoMapper.selectById(videoId);
        if (video != null) {
            video.setStatus(status.name());
            videoMapper.updateById(video);
        }
    }

    private Map<String, Long> parseDurations(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {
            });
        } catch (Exception e) {
            return new ConcurrentHashMap<>();
        }
    }

    private String writeDurations(Map<String, Long> durations) {
        try {
            return objectMapper.writeValueAsString(durations);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
