package com.flashsale.auth.infrastructure;

import com.flashsale.auth.domain.AccessTokenRevocation;
import com.flashsale.shared.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Deny list {@code jwt:deny:{jti}} that expires together with the token it blocks. */
@Component
class RedisAccessTokenRevocation implements AccessTokenRevocation {

    private final StringRedisTemplate redis;
    private final BusinessClock clock;

    RedisAccessTokenRevocation(StringRedisTemplate redis, BusinessClock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        Duration remaining = Duration.between(clock.now(), expiresAt);
        if (!remaining.isNegative() && !remaining.isZero()) {
            redis.opsForValue().set(key(tokenId), "1", remaining);
        }
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(key(tokenId)));
    }

    private static String key(String tokenId) {
        return "jwt:deny:" + tokenId;
    }
}
