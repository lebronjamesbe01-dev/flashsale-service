package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSaleItemStore;
import com.flashsale.flashsale.domain.FlashSaleItemStore.ItemSnapshot;
import com.flashsale.flashsale.domain.StockGate;
import com.flashsale.shared.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings the Redis counters of open items back in line with the database.
 *
 * <p>They can drift when an instance dies between taking a unit in Redis and committing (Redis then
 * shows one unit too few) or while purchases ran database-only during a Redis outage (Redis shows
 * too many). Overwriting with {@code quota - sold} is always safe: if Redis briefly admits one
 * request too many, the database's {@code sold < quota} check rejects it.
 *
 * <p>Every instance runs it; the writes are idempotent, so no leader election is needed.
 */
@Component
class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    private final FlashSaleItemStore items;
    private final StockGate gate;
    private final BusinessClock clock;

    StockReconciler(FlashSaleItemStore items, StockGate gate, BusinessClock clock) {
        this.items = items;
        this.gate = gate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.flash-sale.reconcile-interval:60s}", initialDelay = 5000)
    void reconcile() {
        Instant now = clock.now();
        try {
            for (ItemSnapshot item : items.findOpen(now)) {
                Duration ttl = FlashSaleStockService.stockKeyTtl(now, item.slotEnd());
                gate.overwrite(item.itemId(), item.remaining(), ttl);
            }
        } catch (DataAccessException e) {
            log.warn("Stock reconciliation skipped: {}", e.getMessage());
        }
    }
}
