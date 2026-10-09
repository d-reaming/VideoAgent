package com.videoagent.business.video;

import com.videoagent.middleware.event.EventPublisher;
import com.videoagent.middleware.lock.PipelineLockService;
import com.videoagent.middleware.metrics.PipelineMetricsService;
import com.videoagent.middleware.rag.IndexingService;
import com.videoagent.middleware.task.TaskService;
import com.videoagent.storage.entity.Video;
import com.videoagent.storage.entity.enums.VideoStatus;
import com.videoagent.storage.mapper.VideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoPipelineServiceShouldRunTest {

    @Mock TaskService taskService;
    @Mock TranscriptionService transcriptionService;
    @Mock FrameExtractService frameExtractService;
    @Mock IndexingService indexingService;
    @Mock EventPublisher eventPublisher;
    @Mock VideoMapper videoMapper;
    @Mock PipelineLockService pipelineLock;
    @Mock PipelineMetricsService metrics;
    @Mock ExecutorService pipelineExecutor;

    @InjectMocks VideoPipelineService service;

    private Video video;

    @BeforeEach
    void setup() {
        video = new Video();
        video.setId(23L);
    }

    private boolean shouldRun(Long videoId, VideoStatus expected) {
        return ReflectionTestUtils.invokeMethod(service, "shouldRun", videoId, expected);
    }

    @Test
    void statusMatches_returnsTrue() {
        video.setStatus(VideoStatus.EMBEDDING.name());
        when(videoMapper.selectById(23L)).thenReturn(video);
        assertTrue(shouldRun(23L, VideoStatus.EMBEDDING));
    }

    @Test
    void statusMismatch_returnsFalse() {
        video.setStatus(VideoStatus.READY.name());
        when(videoMapper.selectById(23L)).thenReturn(video);
        assertFalse(shouldRun(23L, VideoStatus.EMBEDDING));
    }

    @Test
    void videoMissing_returnsFalse() {
        when(videoMapper.selectById(23L)).thenReturn(null);
        assertFalse(shouldRun(23L, VideoStatus.EMBEDDING));
    }

    @Test
    void eachStageRequiresExactExpectedState() {
        // 幂等守卫契约：每阶段只有「当前状态==期望状态」才放行，重放/重复投递必须被挡住
        Object[][] matrix = {
                {VideoStatus.UPLOADED, VideoStatus.UPLOADED},
                {VideoStatus.TRANSCRIBING, VideoStatus.TRANSCRIBING},
                {VideoStatus.FRAMING, VideoStatus.FRAMING},
                {VideoStatus.EMBEDDING, VideoStatus.EMBEDDING},
        };
        for (Object[] row : matrix) {
            VideoStatus cur = (VideoStatus) row[0];
            VideoStatus expected = (VideoStatus) row[1];
            video.setStatus(cur.name());
            when(videoMapper.selectById(23L)).thenReturn(video);
            assertTrue(shouldRun(23L, expected), "状态匹配应放行: " + cur);
            // 相临期望（差异性探测）
            for (VideoStatus other : VideoStatus.values()) {
                if (other == expected) {
                    continue;
                }
                assertFalse(shouldRun(23L, other), "状态不匹配应拦截: cur=" + cur + " expected=" + other);
            }
        }
    }
}