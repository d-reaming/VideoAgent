package com.videoagent.business.evidence;

import com.videoagent.storage.entity.FrameRecord;
import com.videoagent.storage.entity.TranscriptSegment;
import com.videoagent.storage.entity.VideoOcr;
import com.videoagent.storage.mapper.FrameRecordMapper;
import com.videoagent.storage.mapper.TranscriptSegmentMapper;
import com.videoagent.storage.mapper.VideoOcrMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceVerifierTest {

    @Mock TranscriptSegmentMapper segmentMapper;
    @Mock VideoOcrMapper ocrMapper;
    @Mock FrameRecordMapper frameMapper;

    @InjectMocks ReferenceVerifier verifier;

    private void stubSources() {
        TranscriptSegment s = new TranscriptSegment();
        s.setVideoId(1L);
        s.setStartSec(60);
        s.setEndSec(120);
        s.setText("光照和材质如何渲染 本视频使用物理渲染管线");
        when(segmentMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(s));

        VideoOcr o = new VideoOcr();
        o.setVideoId(1L);
        o.setFrameTime(100);
        o.setText("材质编辑器");
        when(ocrMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(o));

        when(frameMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    }

    private ReferenceVerifier.Verification verify(String type, Integer start, Integer end, String quote) {
        stubSources();
        return verifier.verifyOne(1L, new ReferenceVerifier.VerificationSource(type, start, end, quote));
    }

    @Test
    void validTimestampAndText_verified() {
        var v = verify("TEXT", 70, 75, "物理渲染管线");
        assertTrue(v.verified(), v.reason());
    }

    @Test
    void validTimestampQuotedOcr_verified() {
        var v = verify("OCR", 98, 102, "材质编辑器");
        assertTrue(v.verified(), v.reason());
    }

    @Test
    void fabricatedTimestamp_notVerified() {
        var v = verify("TEXT", 300, 305, "物理渲染管线");
        assertFalse(v.verified());
        assertTrue(v.reason().contains("无匹配"));
    }

    @Test
    void wrongQuoteAtRightTime_notVerified() {
        var v = verify("TEXT", 70, 75, "完全不存在的虚构内容abc");
        assertFalse(v.verified());
        assertTrue(v.reason().contains("原文中未找到"));
    }

    @Test
    void frameRefWithoutVisionSource_notVerified() {
        var v = verify("FRAME", 100, 100, "某个画面");
        assertFalse(v.verified());
    }

    @Test
    void nullStart_notVerified() {
        var v = verify("TEXT", null, null, "物理渲染管线");
        assertFalse(v.verified());
    }
}