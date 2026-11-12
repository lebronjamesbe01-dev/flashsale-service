package com.flashsale.flashsale.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Atomic, set-based writes on flash sale items that must not go through JPA dirty checking. */
public interface FlashSaleItemStore {

    /**
     * Freezes every item of a slot at what has been sold ({@code quota = sold}) and returns how
     * many unsold units each product gets back. The row locks taken here serialise with purchases,
     * so a purchase arriving after the freeze sees {@code sold < quota} as false.
     */
    List<ReleasedQuota> freezeSlot(UUID slotId, Instant now);

    record ReleasedQuota(UUID productId, int quantity) {}
}
