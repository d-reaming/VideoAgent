package com.videoagent.access.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.access.dto.ApiResponse;
import com.videoagent.middleware.jwt.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器：对受保护 /api/** 校验 Authorization: Bearer &lt;token&gt;。
 * 校验成功把 userId 写入 {@link UserContext}；失败返回 401。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7);
        } else {
            // 静态资源（<video>/<img>）无法携带 Authorization 头，回退读取 ?token= 查询参数
            String q = request.getParameter("token");
            if (q != null && !q.isBlank()) {
                token = q;
            }
        }
        Long userId = token == null ? null : jwtUtil.verifyUserId(token);
        if (userId == null) {
            writeError(response, 401, "未登录或登录已过期");
            return false;
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserContext.clear();
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
    }
}