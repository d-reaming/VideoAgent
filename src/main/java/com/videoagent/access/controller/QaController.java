package com.videoagent.access.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.dto.ApiResponse;
import com.videoagent.access.dto.ChatMessageDto;
import com.videoagent.access.dto.QaRequest;
import com.videoagent.access.dto.QaResponse;
import com.videoagent.access.dto.QaResponse.ReferenceDto;
import com.videoagent.business.harness.ChatSessionService;
import com.videoagent.business.harness.QaFacade;
import com.videoagent.business.video.VideoAccessService;
import com.videoagent.storage.entity.ChatMessage;
import com.videoagent.storage.mapper.ChatMessageMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答接口（亮点 A/C 展示入口）。
 */
@Tag(name = "问答")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QaController {

    private final QaFacade qaFacade;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;
    private final VideoAccessService videoAccessService;

    @Operation(summary = "发起问答（快通道或多 Agent 编排通道由规则层自动决策）")
    @PostMapping("/qa")
    public ApiResponse<QaResponse> ask(@Valid @RequestBody QaRequest request) {
        return ApiResponse.ok(qaFacade.ask(request));
    }

    @Operation(summary = "查询会话历史消息")
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageDto>> messages(@PathVariable Long sessionId) {
        chatSessionService.owned(sessionId);
        List<ChatMessage> list = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));
        List<ChatMessageDto> dtos = new ArrayList<>();
        for (ChatMessage m : list) {
            dtos.add(new ChatMessageDto(m.getId(), m.getRole(), m.getContent(), parseRefs(m.getRefs()),
                    m.getCreatedAt()));
        }
        return ApiResponse.ok(dtos);
    }

    @Operation(summary = "查询某视频下的历史会话列表")
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionService.SessionSummary>> sessions(@RequestParam Long videoId) {
        videoAccessService.owned(videoId);
        return ApiResponse.ok(chatSessionService.listByVideo(videoId));
    }

    private List<ReferenceDto> parseRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ReferenceDto>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
