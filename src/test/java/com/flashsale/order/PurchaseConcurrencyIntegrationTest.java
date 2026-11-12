package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flashsale.support.FlashSaleFixtures;
import com.flashsale.support.FlashSaleFixtures.OpenSlot;
import com.flashsale.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The core guarantees of the assignment, checked against real Postgres and Redis with many
 * concurrent HTTP requests: never sell more than the quota, never more than one flash sale product
 * per user per day, and money moves exactly with the orders.
 */
@IntegrationTest
class PurchaseConcurrencyIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100000.00");
    private static final BigDecimal START_BALANCE = new BigDecimal("1000000.00");

    @Autowired MockMvc mvc;
    @Autowired FlashSaleFixtures fixtures;
    @Autowired StringRedisTemplate redis;

    private OpenSlot slot;

    @AfterEach
    void closeSlot() {
        if (slot != null) {
            fixtures.close(slot);
        }
    }

    @Test
    void thousandBuyersForHundredUnits_exactlyHundredOrders() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 100);
        UUID item = slot.item(0);
        List<UUID> users = fixtures.usersWithBalance(1000, START_BALANCE);

        Map<Integer, Long> statuses = buyConcurrently(users, item);

        assertThat(statuses.get(201)).isEqualTo(100L);
        assertThat(statuses.get(409)).isEqualTo(900L);
        assertThat(fixtures.sold(item)).isEqualTo(100);
        assertThat(fixtures.orderCount(item)).isEqualTo(100);
        assertMoneyMatchesOrders(users, 100);
    }

    @Test
    void databaseAloneStillPreventsOversellWhenTheRedisCounterIsWrong() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 50);
        UUID item = slot.item(0);
        // Simulate a drifted gate (e.g. Redis restored from an old snapshot): it lets everyone in.
        redis.opsForValue().set("fs:stock:" + item, "100000", Duration.ofMinutes(10));
        List<UUID> users = fixtures.usersWithBalance(400, START_BALANCE);

        Map<Integer, Long> statuses = buyConcurrently(users, item);

        assertThat(statuses.get(201)).isEqualTo(50L);
        assertThat(fixtures.sold(item)).isEqualTo(50);
        assertThat(fixtures.orderCount(item)).isEqualTo(50);
        assertMoneyMatchesOrders(users, 50);
    }

    @Test
    void oneUserHammeringFiveItems_getsExactlyOneOrderToday() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 10, 10, 10, 10, 10);
        UUID user = fixtures.usersWithBalance(1, START_BALANCE).getFirst();

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID item = slot.item(i % 5);
            calls.add(() -> buy(user, item, UUID.randomUUID().toString()));
        }
        Map<Integer, Long> statuses = runTogether(calls);

        assertThat(statuses.get(201)).isEqualTo(1L);
        assertThat(fixtures.usersWithMoreThanOneOrderToday(List.of(user))).isZero();
        int totalSold = slot.itemIds().stream().mapToInt(fixtures::sold).sum();
        assertThat(totalSold).isEqualTo(1);
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheSameOrder() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 5);
        UUID user = fixtures.usersWithBalance(1, START_BALANCE).getFirst();
        String key = UUID.randomUUID().toString();

        var first = mvc.perform(request(user, slot.item(0), key)).andReturn().getResponse();
        var second = mvc.perform(request(user, slot.item(0), key)).andReturn().getResponse();

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(orderId(second.getContentAsString()))
                .isEqualTo(orderId(first.getContentAsString()));
        assertThat(fixtures.sold(slot.item(0))).isEqualTo(1);
    }

    @Test
    void insufficientBalanceSellsNothingAndKeepsTheUnitForOthers() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 1);
        UUID poor = fixtures.usersWithBalance(1, new BigDecimal("10.00")).getFirst();
        UUID rich = fixtures.usersWithBalance(1, START_BALANCE).getFirst();

        assertThat(buy(poor, slot.item(0), UUID.randomUUID().toString())).isEqualTo(409);
        assertThat(buy(rich, slot.item(0), UUID.randomUUID().toString())).isEqualTo(201);
        assertThat(fixtures.sold(slot.item(0))).isEqualTo(1);
    }

    @Test
    void purchaseWithoutIdempotencyKeyIsRejected() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 1);
        UUID user = fixtures.usersWithBalance(1, START_BALANCE).getFirst();
        int status =
                mvc.perform(
                                post("/api/v1/flash-sale/items/" + slot.item(0) + "/purchase")
                                        .with(asUser(user)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
        assertThat(status).isEqualTo(400);
    }

    private Map<Integer, Long> buyConcurrently(List<UUID> users, UUID item) throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (UUID user : users) {
            calls.add(() -> buy(user, item, UUID.randomUUID().toString()));
        }
        return runTogether(calls);
    }

    /** Starts every call at the same instant on virtual threads to maximise contention. */
    private static Map<Integer, Long> runTogether(List<Callable<Integer>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<Integer> call : calls) {
                futures.add(
                        pool.submit(() -> {
                            start.await();
                            return call.call();
                        }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        }
    }

    private int buy(UUID user, UUID item, String key) throws Exception {
        return mvc.perform(request(user, item, key)).andReturn().getResponse().getStatus();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            request(UUID user, UUID item, String key) {
        return post("/api/v1/flash-sale/items/" + item + "/purchase")
                .header("Idempotency-Key", key)
                .with(asUser(user));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asUser(
            UUID user) {
        return jwt().jwt(j -> j.subject(user.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private void assertMoneyMatchesOrders(List<UUID> users, int orders) {
        BigDecimal before = START_BALANCE.multiply(BigDecimal.valueOf(users.size()));
        BigDecimal spent = PRICE.multiply(BigDecimal.valueOf(orders));
        assertThat(fixtures.totalBalance(users)).isEqualByComparingTo(before.subtract(spent));
        assertThat(fixtures.usersWithMoreThanOneOrderToday(users)).isZero();
    }

    private static String orderId(String json) {
        int at = json.indexOf("\"orderId\":\"") + 11;
        return json.substring(at, json.indexOf('"', at));
    }
}
