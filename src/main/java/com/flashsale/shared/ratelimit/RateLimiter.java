package com.flashsale.shared.ratelimit;

import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter shared by all instances through Redis. Counting happens in one Lua call
 * (increment + set expiry on first hit), so concurrent requests cannot slip past the limit.
 */
@Component
public class RateLimiter {

    private static final RedisScript<Long> HIT =
            RedisScript.of(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
                    return count
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * @param rule short name of the protected action, e.g. {@code login-ip}
     * @param subject who is limited: client IP or normalized identifier
     * @throws BusinessException TOO_MANY_REQUESTS when the window is exhausted
     */
    public void check(String rule, String subject, int limit, Duration window) {
        if (!properties.enabled()) {
            return;
        }
        Long count =
                redis.execute(
                        HIT,
                        List.of("rl:" + rule + ":" + subject),
                        String.valueOf(window.toMillis()));
        if (count != null && count > limit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
