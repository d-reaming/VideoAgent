package com.videoagent.middleware.task;

import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.mapper.TaskRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 自动重试调度器：退避到期才重投、超上限收编死信、映射复用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskRetrySchedulerTest {

    @Mock TaskRecordMapper taskRecordMapper;
    @Mock TaskService taskService;
    @InjectMocks TaskRetryScheduler scheduler;

    private TaskRecord task(Long videoId, String stage, int retryCount, LocalDateTime updatedAt) {
        TaskRecord t = new TaskRecord();
        t.setVideoId(videoId);
        t.setStage(stage);
        t.setStatus(TaskRecord.STATUS_FAILED);
        t.setRetryCount(retryCount);
        t.setUpdatedAt(updatedAt);
        return t;
    }

    @Test
    void retryCount_upToMax_marksDead_notRetried() {
        when(taskRecordMapper.selectList(any())).thenReturn(List.of(task(23L, "FRAMING", 3, LocalDateTime.now())));
        scheduler.scanAndRetry();
        verify(taskService).markDead(23L, "重试次数已达上限(3次)");
        verify(taskService, never()).retry(anyLong());
    }

    @Test
    void backoffNotDue_skipsRetry() {
        // retryCount=1 → 30s 退避，updated_at 距今 5s，未到期 → 不重投
        when(taskRecordMapper.selectList(any()))
                .thenReturn(List.of(task(23L, "TRANSCRIBING", 1, LocalDateTime.now().minusSeconds(5))));
        scheduler.scanAndRetry();
        verify(taskService, never()).retry(anyLong());
    }

    @Test
    void backoffDue_retriesStageTopic() {
        // retryCount=1 → 30s 退避，updated_at 距今 40s → 到期重投
        when(taskRecordMapper.selectList(any()))
                .thenReturn(List.of(task(23L, "EMBEDDING", 1, LocalDateTime.now().minusSeconds(40))));
        scheduler.scanAndRetry();
        verify(taskService).retry(23L);
    }

    @Test
    void noFailedTasks_noop() {
        when(taskRecordMapper.selectList(any())).thenReturn(List.of());
        scheduler.scanAndRetry();
        verify(taskService, never()).retry(anyLong());
        verify(taskService, never()).markDead(anyLong(), anyString());
    }

    @Test
    void backoffSeconds_mapping() {
        assertEquals(0, TaskRetryScheduler.backoffSeconds(0));
        assertEquals(30, TaskRetryScheduler.backoffSeconds(1));
        assertEquals(60, TaskRetryScheduler.backoffSeconds(2));
        assertEquals(120, TaskRetryScheduler.backoffSeconds(3));
        assertEquals(120, TaskRetryScheduler.backoffSeconds(9));
    }
}