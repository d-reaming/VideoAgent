package com.videoagent.middleware.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.exception.BusinessException;
import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.event.Topics;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.storage.entity.TaskRecord;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.TaskRecordMapper;
import com.videoagent.storage.mapper.VideoMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskServiceRetryTest {

    @Mock VideoMapper videoMapper;
    @Mock TaskRecordMapper taskRecordMapper;
    @Mock EventPublisher eventPublisher;
    @Mock StringRedisTemplate redisTemplate;
    @Mock PipelineLockService pipelineLock;

    @InjectMocks TaskService taskService;

    private Video video;

    @BeforeEach
    void setup() {
        video = new Video();
        video.setId(23L);
        video.setStatus(VideoStatus.FAILED.name());
        // 让锁默认执行任务体并返回 true（lenient：个别用例会覆写此桩）
        lenient().when(pipelineLock.tryRun(anyString(), anyLong(), any(Runnable.class))).thenAnswer(inv -> {
            Runnable r = inv.getArgument(2);
            r.run();
            return true;
        });
    }

    private void stubTask(String stage, int retryCount) {
        TaskRecord task = new TaskRecord();
        task.setStage(stage);
        task.setRetryCount(retryCount);
        when(taskRecordMapper.selectOne(any())).thenReturn(task);
    }

    @Test
    void nonFailedVideo_rejects() {
        video.setStatus(VideoStatus.READY.name());
        when(videoMapper.selectById(23L)).thenReturn(video);
        BusinessException ex = assertThrows(BusinessException.class, () -> taskService.retry(23L));
        assertTrue(ex.getMessage().contains("仅 FAILED"));
        verify(eventPublisher, never()).publish(any(), anyLong());
    }

    @Test
    void retryCountLimit_rejects() {
        when(videoMapper.selectById(23L)).thenReturn(video);
        stubTask("FRAMING", 3);
        BusinessException ex = assertThrows(BusinessException.class, () -> taskService.retry(23L));
        assertTrue(ex.getMessage().contains("上限"));
        verify(eventPublisher, never()).publish(any(), anyLong());
    }

    @Test
    void lockNotAcquired_noPublish() {
        when(videoMapper.selectById(23L)).thenReturn(video);
        when(pipelineLock.tryRun(anyString(), anyLong(), any(Runnable.class))).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> taskService.retry(23L));
        assertTrue(ex.getMessage().contains("繁忙"));
        verify(eventPublisher, never()).publish(any(), anyLong());
    }

    @Test
    void framingStage_resetsToTranscribing_publishesTranscribed() {
        when(videoMapper.selectById(23L)).thenReturn(video);
        stubTask("FRAMING", 0);
        taskService.retry(23L);
        verify(eventPublisher).publish(Topics.VIDEO_TRANSCRIBED, 23L);
        ArgumentCaptor<Video> cap = ArgumentCaptor.forClass(Video.class);
        verify(videoMapper).updateById(cap.capture());
        assertEquals(VideoStatus.TRANSCRIBING.name(), cap.getValue().getStatus());
    }

    @Test
    void embeddingStage_resetsToFraming_publishesFramed() {
        when(videoMapper.selectById(23L)).thenReturn(video);
        stubTask("EMBEDDING", 0);
        taskService.retry(23L);
        verify(eventPublisher).publish(Topics.VIDEO_FRAMED, 23L);
        ArgumentCaptor<Video> cap = ArgumentCaptor.forClass(Video.class);
        verify(videoMapper).updateById(cap.capture());
        assertEquals(VideoStatus.FRAMING.name(), cap.getValue().getStatus());
    }
}