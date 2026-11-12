package com.flashsale.auth.infrastructure;

import com.flashsale.auth.domain.Identifier;
import com.flashsale.auth.domain.OtpStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * OTP state in Redis so every app instance sees the same code and attempt counter.
 *
 * <p>Key {@code otp:{identifier}} is a hash {@code {code: <hmac>, attempts: <n>}} with a TTL.
 */
@Component
class RedisOtpStore implements OtpStore {

    /** Stores the code hash and resets the attempt counter in one step, with the TTL. */
    private static final RedisScript<Long> SAVE =
            RedisScript.of(
                    """
                    redis.call('DEL', KEYS[1])
                    redis.call('HSET', KEYS[1], 'code', ARGV[1], 'attempts', 0)
                    redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    return 1
                    """,
                    Long.class);

    /**
     * Counts the attempt first, then returns the hash. Over the limit, the code is deleted so even
     * a correct guess afterwards fails. Returns nil when there is no code.
     */
    private static final RedisScript<String> ATTEMPT =
            RedisScript.of(
                    """
                    if redis.call('EXISTS', KEYS[1]) == 0 then return nil end
                    local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
                    if attempts > tonumber(ARGV[1]) then
                        redis.call('DEL', KEYS[1])
                        return nil
                    end
                    return redis.call('HGET', KEYS[1], 'code')
                    """,
                    String.class);

    private final StringRedisTemplate redis;

    RedisOtpStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(Identifier identifier, String codeHash, Duration ttl) {
        redis.execute(SAVE, List.of(key(identifier)), codeHash, String.valueOf(ttl.toMillis()));
    }

    @Override
    public Optional<String> registerAttempt(Identifier identifier, int maxAttempts) {
        return Optional.ofNullable(
                redis.execute(ATTEMPT, List.of(key(identifier)), String.valueOf(maxAttempts)));
    }

    @Override
    public void delete(Identifier identifier) {
        redis.delete(key(identifier));
    }

    @Override
    public boolean exists(Identifier identifier) {
        return Boolean.TRUE.equals(redis.hasKey(key(identifier)));
    }

    @Override
    public boolean tryStartResendCooldown(Identifier identifier, Duration cooldown) {
        Boolean started =
                redis.opsForValue()
                        .setIfAbsent("otp:cooldown:" + identifier.value(), "1", cooldown);
        return Boolean.TRUE.equals(started);
    }

    private static String key(Identifier identifier) {
        return "otp:" + identifier.value();
    }
}
