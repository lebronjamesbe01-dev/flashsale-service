package com.flashsale.auth.infrastructure;

import com.flashsale.auth.domain.AccessTokenRevocation;
import com.flashsale.shared.logging.ThrottledWarning;
import com.flashsale.shared.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Deny list {@code jwt:deny:{jti}} that expires together with the token it blocks.
 *
 * <p>Fails open: when Redis is unreachable, tokens are still accepted (signature and expiry still
 * apply) so an outage does not take every authenticated endpoint down. The cost is that a token
 * logged out during the outage stays usable until it expires.
 */
@Component
class RedisAccessTokenRevocation implements AccessTokenRevocation {

    private static final ThrottledWarning redisDown =
            new ThrottledWarning(
                    LoggerFactory.getLogger(RedisAccessTokenRevocation.class),
                    Duration.ofSeconds(30));

    private final StringRedisTemplate redis;
    private final BusinessClock clock;

    RedisAccessTokenRevocation(StringRedisTemplate redis, BusinessClock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        Duration remaining = Duration.between(clock.now(), expiresAt);
        if (!remaining.isPositive()) {
            return;
        }
        try {
            redis.opsForValue().set(key(tokenId), "1", remaining);
        } catch (DataAccessException e) {
            warnRedisDown("revoke", e);
        }
    }

    @Override
    public boolean isRevoked(String tokenId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(tokenId)));
        } catch (DataAccessException e) {
            warnRedisDown("revocation check", e);
            return false;
        }
    }

    private void warnRedisDown(String action, DataAccessException e) {
        redisDown.warn("Redis unavailable, JWT {} skipped (fail open): {}", action, e.getMessage());
    }

    private static String key(String tokenId) {
        return "jwt:deny:" + tokenId;
    }
}
