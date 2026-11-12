package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flashsale.shared.time.BusinessClock;
import com.flashsale.support.FlashSaleFixtures;
import com.flashsale.support.FlashSaleFixtures.OpenSlot;
import com.flashsale.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** How the Redis daily flag is repaired when the database rejects a purchase as a duplicate. */
@IntegrationTest
class IdempotencyKeyReuseIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100000.00");

    @Autowired MockMvc mvc;
    @Autowired FlashSaleFixtures fixtures;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient jdbc;
    @Autowired BusinessClock clock;

    private OpenSlot slot;

    @AfterEach
    void closeSlot() {
        if (slot != null) {
            fixtures.close(slot);
        }
    }

    @Test
    void oldKeyReusedForFirstPurchaseToday_isRejectedButDoesNotBlockAFreshKey() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 5, 5);
        UUID user = fixtures.usersWithBalance(1, new BigDecimal("1000000.00")).getFirst();
        String oldKey = UUID.randomUUID().toString();
        insertOrderFrom(user, slot.item(0), clock.today().minusDays(1), oldKey);

        assertThat(buy(user, slot.item(1), oldKey)).isEqualTo(422);
        // Nothing was bought today, so the daily flag must not linger from the failed attempt.
        assertThat(redis.hasKey(flagKey(user))).isFalse();
        assertThat(buy(user, slot.item(1), UUID.randomUUID().toString())).isEqualTo(201);
        assertThat(fixtures.sold(slot.item(1))).isEqualTo(1);
    }

    @Test
    void userWhoReallyBoughtTodayAfterFlagWasLost_getsTheFlagUntilEndOfDay() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 5, 5);
        UUID user = fixtures.usersWithBalance(1, new BigDecimal("1000000.00")).getFirst();
        assertThat(buy(user, slot.item(0), UUID.randomUUID().toString())).isEqualTo(201);
        redis.delete(flagKey(user)); // as after a Redis outage / flush

        assertThat(buy(user, slot.item(1), UUID.randomUUID().toString())).isEqualTo(409);

        Long ttlSeconds = redis.getExpire(flagKey(user));
        assertThat(ttlSeconds).isNotNull().isGreaterThan(120L);
        assertThat(fixtures.sold(slot.item(1))).isZero();
    }

    private String flagKey(UUID user) {
        return "fs:bought:" + clock.today() + ":" + user;
    }

    private void insertOrderFrom(UUID user, UUID item, LocalDate saleDate, String key) {
        jdbc.sql(
                        "insert into flash_orders (id, user_id, item_id, slot_id, product_id,"
                                + " sale_date, price, status, idempotency_key, created_at)"
                                + " values (?, ?, ?, ?, ?, ?, ?, 'PAID', ?, ?)")
                .params(
                        UUID.randomUUID(),
                        user,
                        item,
                        slot.slotId(),
                        fixtures.productOf(item),
                        saleDate,
                        PRICE,
                        key,
                        OffsetDateTime.now().minusDays(1))
                .update();
    }

    private int buy(UUID user, UUID item, String key) throws Exception {
        return mvc.perform(
                        post("/api/v1/flash-sale/items/" + item + "/purchase")
                                .header("Idempotency-Key", key)
                                .with(
                                        jwt().jwt(j -> j.subject(user.toString()))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_USER"))))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
