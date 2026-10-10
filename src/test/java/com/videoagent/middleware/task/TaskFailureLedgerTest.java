package com.videoagent.middleware.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.storage.entity.TaskFailure;
import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.TaskFailureMapper;
import com.videoagent.storage.mapper.TaskRecordMapper;
import com.videoagent.storage.mapper.VideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 失败台账与死信终态：每次失败落账一行、重试耗尽置 DEAD 且落账带 dead_at。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskFailureLedgerTest {

    @Mock TaskRecordMapper taskRecordMapper;
    @Mock TaskFailureMapper taskFailureMapper;
    @Mock VideoMapper videoMapper;
    @Mock EventPublisher eventPublisher;
    @Mock StringRedisTemplate redisTemplate;
    @Mock PipelineLockService pipelineLock;
    @Mock ObjectMapper objectMapper;

    @InjectMocks TaskService taskService;

    private Video video;

    @BeforeEach
    void setup() {
        video = new Video();
        video.setId(23L);
        video.setStatus(VideoStatus.READY.name());
        when(videoMapper.selectById(23L)).thenReturn(video);
    }

    private void stubTask(int retryCount, String stage) {
        TaskRecord task = new TaskRecord();
        task.setVideoId(23L);
        task.setStage(stage);
        task.setStatus(TaskRecord.STATUS_FAILED);
        task.setRetryCount(retryCount);
        when(taskRecordMapper.selectOne(any())).thenReturn(task);
    }

    @Test
    void failure_writesLedgerRow_forCurrentAttempt() {
        stubTask(1, "FRAMING");
        taskService.fail(23L, "FRAMING", "boom");
        ArgumentCaptor<TaskFailure> cap = ArgumentCaptor.forClass(TaskFailure.class);
        verify(taskFailureMapper, atLeastOnce()).insert(cap.capture());
        // 第 2 次尝试，未达上限 → dead_at 为空（仍可自动重试）
        assertTrue(cap.getAllValues().stream().anyMatch(f ->
                f.getAttempt() == 2 && f.getStage().equals("FRAMING") && f.getDeadAt() == null));
        verify(videoMapper).updateById((Video) taskFailureLedgerTestVideoCap.capture());
        assertEquals(VideoStatus.FAILED.name(), taskFailureLedgerTestVideoCap.getValue().getStatus());
    }

    private final ArgumentCaptor<Video> taskFailureLedgerTestVideoCap = ArgumentCaptor.forClass(Video.class);

    @Test
    void retryExhausted_marksTaskDead_andLedgerDeadAt() {
        // 已失败 2 次，本次第 3 次 → 达到 AUTO_RETRY_MAX，任务转 DEAD + 落账 dead_at
        stubTask(2, "EMBEDDING");
        taskService.fail(23L, "EMBEDDING", "boom");
        ArgumentCaptor<TaskRecord> taskCap = ArgumentCaptor.forClass(TaskRecord.class);
        verify(taskRecordMapper).updateById((TaskRecord) taskCap.capture());
        assertEquals(TaskRecord.STATUS_DEAD, taskCap.getValue().getStatus());
        assertEquals(3, taskCap.getValue().getRetryCount());
        ArgumentCaptor<TaskFailure> fCap = ArgumentCaptor.forClass(TaskFailure.class);
        verify(taskFailureMapper, atLeastOnce()).insert(fCap.capture());
        assertTrue(fCap.getAllValues().stream().anyMatch(f -> f.getDeadAt() != null),
                "重试耗尽应有一条死信账（dead_at 非空）");
    }

    @Test
    void markDead_explicit_setsDeadStatus() {
        stubTask(3, "TRANSCRIBING");
        taskService.markDead(23L, "调度器兜底");
        ArgumentCaptor<TaskRecord> cap = ArgumentCaptor.forClass(TaskRecord.class);
        verify(taskRecordMapper).updateById((TaskRecord) cap.capture());
        assertEquals(TaskRecord.STATUS_DEAD, cap.getValue().getStatus());
    }

    @Test
    void stageMapping_unknownStage_rejects() {
        TaskRecord task = new TaskRecord();
        task.setVideoId(23L);
        task.setStage("NOPE");
        task.setStatus(TaskRecord.STATUS_FAILED);
        task.setRetryCount(0);
        when(taskRecordMapper.selectOne(any())).thenReturn(task);
        when(pipelineLock.tryRun(anyString(), anyLong(), any(Runnable.class))).thenAnswer(inv -> {
            Runnable r = inv.getArgument(2);
            r.run();
            return true;
        });
        assertThrows(BusinessException.class, () -> taskService.retry(23L));
        verify(eventPublisher, never()).publish(any(), anyLong());
    }
}