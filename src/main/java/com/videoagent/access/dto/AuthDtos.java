package com.videoagent.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 认证相关 DTO。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 32, message = "用户名长度 2-32")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 4, max = 64, message = "密码长度 4-64")
            String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record LoginResponse(String token, Long userId, String username) {
    }
}