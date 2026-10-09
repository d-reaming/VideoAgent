package com.videoagent.middleware.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 签发/校验（jjwt）。
 * 采用 HS256，密钥来自配置；校验失败/过期统一返回 null，由拦截器判为 401。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final Duration expire;

    public JwtUtil(@Value("${video-agent.auth.jwt-secret}") String secret,
                   @Value("${video-agent.auth.expire-hours:72}") long expireHours) {
        // 安全加固：生产必须以环境变量注入强密钥（HS256 至少 32 字节），缺失/过短直接拒绝启动，
        // 避免使用仓库内默认固定密钥被离线伪造 token。
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT 密钥未配置或长度不足 32 字节：必须设置 VIDEO_AGENT_JWT_SECRET 环境变量");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expire = Duration.ofHours(expireHours);
    }

    public String create(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expire.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 userId；无效/过期返回 null */
    public Long verifyUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Object uid = claims.get("uid");
            return uid == null ? null : Long.valueOf(uid.toString());
        } catch (Exception e) {
            return null;
        }
    }
}