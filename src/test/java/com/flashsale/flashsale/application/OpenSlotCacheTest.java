package com.flashsale.flashsale.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenSlotCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-14T05:00:00Z");
    private static final SlotView SLOT =
            new SlotView(UUID.randomUUID(), "noon", T0, T0.plusSeconds(10), "PUBLISHED", List.of());

    private final AtomicInteger loads = new AtomicInteger();
    private final OpenSlotCache cache = new OpenSlotCache(Duration.ofSeconds(1));

    private SlotView load() {
        loads.incrementAndGet();
        return SLOT;
    }

    @Test
    void reusesTheAnswerWithinTheTtlAndReloadsAfter() {
        assertThat(cache.get(T0, this::load)).isEqualTo(SLOT);
        assertThat(cache.get(T0.plusMillis(999), this::load)).isEqualTo(SLOT);
        assertThat(loads).hasValue(1);

        cache.get(T0.plusSeconds(1), this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void neverShowsASlotAfterItsEndTime() {
        Instant justBeforeEnd = SLOT.endAt().minusMillis(500);
        assertThat(cache.get(justBeforeEnd, this::load)).isEqualTo(SLOT);
        assertThat(cache.get(SLOT.endAt(), this::load)).isNull();
        assertThat(loads).hasValue(1);
    }

    @Test
    void cachesTheAbsenceOfAnOpenSlotToo() {
        assertThat(cache.get(T0, () -> null)).isNull();
        assertThat(cache.get(T0.plusMillis(500), this::load)).isNull();
        assertThat(loads).hasValue(0);
    }

    @Test
    void zeroTtlAlwaysLoads() {
        OpenSlotCache uncached = new OpenSlotCache(Duration.ZERO);
        uncached.get(T0, this::load);
        uncached.get(T0, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void aBurstOfConcurrentMissesLoadsOnce() throws Exception {
        int callers = 200;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    start.await();
                    return cache.get(
                            T0,
                            () -> {
                                sleep(50);
                                return load();
                            });
                });
            }
            start.countDown();
        }
        assertThat(loads).hasValue(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
