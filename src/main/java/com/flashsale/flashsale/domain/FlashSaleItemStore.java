package com.flashsale.flashsale.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Atomic, set-based writes on flash sale items that must not go through JPA dirty checking. */
public interface FlashSaleItemStore {

    /**
     * Freezes every item of a slot at what has been sold ({@code quota = sold}) and returns how
     * many unsold units each product gets back. The row locks taken here serialise with purchases,
     * so a purchase arriving after the freeze sees {@code sold < quota} as false.
     */
    List<ReleasedQuota> freezeSlot(UUID slotId, Instant now);

    /**
     * Sells one unit: {@code sold + 1} only if the slot is open at {@code now} and {@code sold <
     * quota}. The condition is re-checked under the row lock, so concurrent buyers can never push
     * sold past quota. Returns false when nothing was sold.
     */
    boolean takeOne(UUID itemId, Instant now);

    Optional<ItemSnapshot> find(UUID itemId);

    /** Items of slots open at {@code now}, with their database remaining quantity. */
    List<ItemSnapshot> findOpen(Instant now);

    record ReleasedQuota(UUID productId, int quantity) {}

    record ItemSnapshot(
            UUID itemId,
            UUID slotId,
            UUID productId,
            BigDecimal salePrice,
            int remaining,
            SlotStatus slotStatus,
            Instant slotStart,
            Instant slotEnd) {

        public boolean isOpen(Instant now) {
            return slotStatus == SlotStatus.PUBLISHED
                    && !now.isBefore(slotStart)
                    && now.isBefore(slotEnd);
        }
    }
}
