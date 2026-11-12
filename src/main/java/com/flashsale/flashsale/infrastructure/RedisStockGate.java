package com.flashsale.flashsale.infrastructure;

import com.flashsale.flashsale.domain.StockGate;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis keys:
 *
 * <ul>
 *   <li>{@code fs:stock:{itemId}} remaining units admitted by the gate
 *   <li>{@code fs:bought:{yyyy-MM-dd}:{userId}} item the user took today
 * </ul>
 *
 * Checks and writes happen inside Lua scripts, which Redis runs atomically: no other command can
 * run between "is there stock?" and "take one". Both keys of a script must live on one node, so a
 * Redis Cluster would need hash tags; a single node (or primary/replica) is assumed here.
 */
@Component
class RedisStockGate implements StockGate {

    private static final RedisScript<Long> ADMIT =
            RedisScript.of(
                    """
                    local stock = redis.call('GET', KEYS[1])
                    if not stock then return 3 end
                    if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
                    if tonumber(stock) <= 0 then return 1 end
                    redis.call('DECR', KEYS[1])
                    redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
                    return 0
                    """,
                    Long.class);

    /**
     * Only clears the daily flag if it still points at this item (never someone else's purchase).
     */
    private static final RedisScript<Long> RELEASE =
            RedisScript.of(
                    """
                    if ARGV[2] == '1' and redis.call('GET', KEYS[2]) == ARGV[1] then
                        redis.call('DEL', KEYS[2])
                    end
                    if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
                    return 1
                    """,
                    Long.class);

    /** The database found no stock: clear the user's flag and admit nobody else. */
    private static final RedisScript<Long> SOLD_OUT =
            RedisScript.of(
                    """
                    if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
                    if redis.call('EXISTS', KEYS[1]) == 1 then
                        redis.call('SET', KEYS[1], 0, 'KEEPTTL')
                    end
                    return 1
                    """,
                    Long.class);

    private final StringRedisTemplate redis;

    RedisStockGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Admission tryAdmit(UUID itemId, UUID userId, LocalDate saleDate, Duration pendingTtl) {
        Long result =
                redis.execute(
                        ADMIT,
                        List.of(stockKey(itemId), boughtKey(saleDate, userId)),
                        itemId.toString(),
                        String.valueOf(pendingTtl.toMillis()));
        return switch (result == null ? 3 : result.intValue()) {
            case 0 -> Admission.ADMITTED;
            case 1 -> Admission.SOLD_OUT;
            case 2 -> Admission.ALREADY_PURCHASED_TODAY;
            default -> Admission.NOT_INITIALIZED;
        };
    }

    @Override
    public void confirm(UUID userId, LocalDate saleDate, Duration dailyFlagTtl) {
        redis.expire(boughtKey(saleDate, userId), dailyFlagTtl);
    }

    @Override
    public void release(UUID itemId, UUID userId, LocalDate saleDate, boolean clearDailyFlag) {
        redis.execute(
                RELEASE,
                List.of(stockKey(itemId), boughtKey(saleDate, userId)),
                itemId.toString(),
                clearDailyFlag ? "1" : "0");
    }

    @Override
    public void markSoldOut(UUID itemId, UUID userId, LocalDate saleDate) {
        redis.execute(
                SOLD_OUT,
                List.of(stockKey(itemId), boughtKey(saleDate, userId)),
                itemId.toString());
    }

    @Override
    public void initialize(UUID itemId, int remaining, Duration ttl) {
        redis.opsForValue().setIfAbsent(stockKey(itemId), String.valueOf(remaining), ttl);
    }

    @Override
    public void overwrite(UUID itemId, int remaining, Duration ttl) {
        redis.opsForValue().set(stockKey(itemId), String.valueOf(remaining), ttl);
    }

    private static String stockKey(UUID itemId) {
        return "fs:stock:" + itemId;
    }

    private static String boughtKey(LocalDate saleDate, UUID userId) {
        return "fs:bought:" + saleDate + ":" + userId;
    }
}
