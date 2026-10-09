package com.videoagent.access.auth;

/**
 * 当前已认证用户上下文（请求级 ThreadLocal）。
 * 由 AuthInterceptor 在解析 JWT 后写入；供限流/审计使用。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}