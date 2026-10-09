package com.videoagent.middleware.rate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 令牌桶限流（Redisson RRateLimiter）。
 * 按 bucket（如 qa:用户Id / model:用户Id）限速；速率未配置时以 trySetRate 惰性初始化。
 * 语义：每 bucket 每秒允许 rate 个请求，超出返回失败，由上层抛 429。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String KEY_PREFIX = "video-agent:rl:";

    private final RedissonClient redisson;

    @Value("${video-agent.limit.qa-rate:5}")
    private double qaRate;

    @Value("${video-agent.limit.model-rate:8}")
    private double modelRate;

    /** 问答限流：每用户每秒 qaRate 次 */
    public boolean tryQa(Long userId) {
        return tryAcquire("qa:" + userId, qaRate);
    }

    /** 模型调用限流：每用户每秒 modelRate 次 */
    public boolean tryModel(Long userId) {
        return tryAcquire("model:" + userId, modelRate);
    }

    private boolean tryAcquire(String bucket, double rate) {
        try {
            RRateLimiter limiter = redisson.getRateLimiter(KEY_PREFIX + bucket);
            limiter.trySetRate(RateType.OVERALL, (long) Math.max(1, rate), 1, RateIntervalUnit.SECONDS);
            return limiter.tryAcquire(1);
        } catch (Exception e) {
            log.warn("[rate-limit] failed bucket={} err={}", bucket, e.getMessage());
            // 限流中间件异常时不阻断业务，放行
            return true;
        }
    }
}