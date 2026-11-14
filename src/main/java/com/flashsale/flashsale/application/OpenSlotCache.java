package com.flashsale.flashsale.application;

import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Per-instance cache of the slot open right now, for {@code GET /flash-sale/current}. When a sale
 * opens, shoppers refresh that page constantly; without this every refresh costs three queries and
 * a pooled connection that purchases need. With it each instance reads the database about once per
 * TTL, and only one request does the read while the others wait for its result.
 *
 * <p>Quantities shown may lag by up to the TTL. That is only display: what can actually be bought
 * is decided by the Redis gate and the database at purchase time. A cached slot is never shown
 * after its end time.
 */
class OpenSlotCache {

    private final Duration ttl;
    private volatile Entry entry;

    OpenSlotCache(Duration ttl) {
        this.ttl = ttl;
    }

    /** The open slot (null if none), from the cache or from {@code loader} when stale. */
    SlotView get(Instant now, Supplier<SlotView> loader) {
        if (ttl.isZero()) {
            return loader.get();
        }
        Entry current = entry;
        if (current == null || !now.isBefore(current.expiresAt())) {
            synchronized (this) {
                current = entry;
                if (current == null || !now.isBefore(current.expiresAt())) {
                    current = new Entry(loader.get(), now.plus(ttl));
                    entry = current;
                }
            }
        }
        SlotView slot = current.slot();
        return slot != null && now.isBefore(slot.endAt()) ? slot : null;
    }

    private record Entry(SlotView slot, Instant expiresAt) {}
}
