package com.videoagent.access.exception;

import com.videoagent.access.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private void assertBusiness(int code, HttpStatus expectedHttp) {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBusiness(new BusinessException(code, "boom-" + code));
        assertEquals(expectedHttp, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(code, resp.getBody().code());
    }

    @Test
    void business400MapsToBadRequest() { assertBusiness(400, HttpStatus.BAD_REQUEST); }

    @Test
    void business401MapsToUnauthorized() { assertBusiness(401, HttpStatus.UNAUTHORIZED); }

    @Test
    void business403MapsToForbidden() { assertBusiness(403, HttpStatus.FORBIDDEN); }

    @Test
    void business404MapsToNotFound() { assertBusiness(404, HttpStatus.NOT_FOUND); }

    @Test
    void business429MapsToTooManyRequests() { assertBusiness(429, HttpStatus.TOO_MANY_REQUESTS); }

    @Test
    void unknownCodeKeepsHttp200WithBody() { assertBusiness(999, HttpStatus.OK); }

    @Test
    void handleOtherDoesNotLeakInternalMessage() {
        ApiResponse<Void> body = handler.handleOther(
                new RuntimeException("psql: relation missing / ffmpeg / C:\\secret\\path"));
        assertEquals(500, body.code());
        assertFalse(body.message().contains("psql"));
        assertFalse(body.message().contains("ffmpeg"));
        assertFalse(body.message().contains("C:\\"));
        assertEquals("系统异常，请稍后重试", body.message());
    }
}