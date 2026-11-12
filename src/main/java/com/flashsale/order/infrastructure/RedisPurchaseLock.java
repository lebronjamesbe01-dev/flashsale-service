package com.flashsale.order.infrastructure;

import com.flashsale.order.domain.PurchaseLock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisPurchaseLock implements PurchaseLock {

    /**
     * Long enough for one purchase, short enough that a crashed instance does not block a retry.
     */
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    RedisPurchaseLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryLock(UUID userId, String idempotencyKey) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(key(userId, idempotencyKey), "1", TTL));
    }

    @Override
    public void unlock(UUID userId, String idempotencyKey) {
        redis.delete(key(userId, idempotencyKey));
    }

    private static String key(UUID userId, String idempotencyKey) {
        return "purchase:lock:" + userId + ":" + idempotencyKey;
    }
}
