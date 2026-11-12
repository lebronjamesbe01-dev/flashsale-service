package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSaleItemStore;
import com.flashsale.flashsale.domain.FlashSaleItemStore.ItemSnapshot;
import com.flashsale.flashsale.domain.StockGate;
import com.flashsale.flashsale.domain.StockGate.Admission;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the purchase flow needs from the flash sale module: check an item is on sale, pass the Redis
 * gate, and sell one unit in the database.
 */
@Service
public class FlashSaleStockService {

    /** Extra lifetime of Redis keys after the slot or day they belong to has ended. */
    private static final Duration GRACE = Duration.ofHours(1);

    /** Longer than the purchase transaction timeout, so a live purchase never loses its flag. */
    static final Duration PENDING_FLAG_TTL = Duration.ofSeconds(60);

    private final FlashSaleItemStore items;
    private final StockGate gate;
    private final BusinessClock clock;
    private final ItemSnapshotCache snapshots;

    public FlashSaleStockService(FlashSaleItemStore items, StockGate gate, BusinessClock clock) {
        this.items = items;
        this.gate = gate;
        this.clock = clock;
        this.snapshots = new ItemSnapshotCache(items, Duration.ofSeconds(1));
    }

    /**
     * The item as it can be bought now, or SLOT_ITEM_NOT_FOUND / FLASH_SALE_NOT_ACTIVE. Item data
     * (price, slot window) cannot change once a slot is open, so it is cached for a second to keep
     * a burst of requests from reading the same row thousands of times.
     */
    public PurchasableItem requireOnSale(UUID itemId, Instant now) {
        ItemSnapshot item = snapshots.get(itemId, now);
        if (item == null) {
            throw new BusinessException(ErrorCode.SLOT_ITEM_NOT_FOUND);
        }
        if (!item.isOpen(now)) {
            throw new BusinessException(ErrorCode.FLASH_SALE_NOT_ACTIVE);
        }
        return new PurchasableItem(
                item.itemId(), item.slotId(), item.productId(), item.salePrice(), item.slotEnd());
    }

    /**
     * Passes the Redis gate or throws SOLD_OUT / ALREADY_PURCHASED_TODAY. On first use of an item
     * the counter is seeded from the database ({@code quota - sold}).
     */
    public void admit(PurchasableItem item, UUID userId, LocalDate saleDate) {
        Admission admission = gate.tryAdmit(item.itemId(), userId, saleDate, PENDING_FLAG_TTL);
        if (admission == Admission.NOT_INITIALIZED) {
            int remaining = items.find(item.itemId()).map(ItemSnapshot::remaining).orElse(0);
            gate.initialize(item.itemId(), remaining, untilSlotEnd(item.slotEnd()));
            admission = gate.tryAdmit(item.itemId(), userId, saleDate, PENDING_FLAG_TTL);
        }
        switch (admission) {
            case ADMITTED -> {}
            case SOLD_OUT -> throw new BusinessException(ErrorCode.SOLD_OUT);
            case ALREADY_PURCHASED_TODAY ->
                    throw new BusinessException(ErrorCode.ALREADY_PURCHASED_TODAY);
            case NOT_INITIALIZED -> throw new IllegalStateException("Stock gate not initialised");
        }
    }

    /** The order committed: the user's daily flag now lives until the end of the sale day. */
    public void confirmAdmission(UUID userId, LocalDate saleDate) {
        gate.confirm(userId, saleDate, untilEndOfDay(saleDate));
    }

    public void cancelAdmission(
            UUID itemId, UUID userId, LocalDate saleDate, boolean clearDailyFlag) {
        gate.release(itemId, userId, saleDate, clearDailyFlag);
    }

    /** The database refused because nothing is left: close the gate for this item. */
    public void cancelAdmissionSoldOut(UUID itemId, UUID userId, LocalDate saleDate) {
        gate.markSoldOut(itemId, userId, saleDate);
    }

    /** Sells one unit inside the caller's transaction, or throws SOLD_OUT. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void takeOne(UUID itemId, Instant now) {
        if (!items.takeOne(itemId, now)) {
            boolean stillOpen = items.find(itemId).map(i -> i.isOpen(now)).orElse(false);
            throw new BusinessException(
                    stillOpen ? ErrorCode.SOLD_OUT : ErrorCode.FLASH_SALE_NOT_ACTIVE);
        }
    }

    private Duration untilEndOfDay(LocalDate saleDate) {
        Instant endOfDay = saleDate.plusDays(1).atStartOfDay(clock.zone()).toInstant();
        return positive(Duration.between(clock.now(), endOfDay).plus(GRACE));
    }

    private Duration untilSlotEnd(Instant slotEnd) {
        return positive(Duration.between(clock.now(), slotEnd).plus(GRACE));
    }

    private static Duration positive(Duration duration) {
        return duration.isNegative() || duration.isZero() ? GRACE : duration;
    }

    public record PurchasableItem(
            UUID itemId, UUID slotId, UUID productId, BigDecimal price, Instant slotEnd) {}
}
