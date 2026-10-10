package com.videoagent.business.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.storage.mapper.QaCheckpointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaCheckpointServiceTest {

    @Mock QaCheckpointMapper mapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks QaCheckpointService service;

    @Test
    void digest_stableForSameQuestion() {
        assertEquals(service.digest("光照怎么渲染"), service.digest(" 光照怎么渲染 "));
        assertNotEquals(service.digest("光照怎么渲染"), service.digest("材质怎么渲染"));
    }

    @Test
    void loadResult_missing_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(service.loadResult(1L, "abc"));
    }
}