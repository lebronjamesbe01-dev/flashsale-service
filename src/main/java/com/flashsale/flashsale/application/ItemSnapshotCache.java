package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSaleItemStore;
import com.flashsale.flashsale.domain.FlashSaleItemStore.ItemSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance, one-second cache of item snapshots for the purchase path. Only data that is frozen
 * while a slot is open is used from it (price, product, slot window); stock counts always come from
 * Redis or the database. Losing it costs one query, so it is not shared state.
 */
class ItemSnapshotCache {

    private final FlashSaleItemStore items;
    private final Duration ttl;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    ItemSnapshotCache(FlashSaleItemStore items, Duration ttl) {
        this.items = items;
        this.ttl = ttl;
    }

    /** Null when the item does not exist. */
    ItemSnapshot get(UUID itemId, Instant now) {
        Entry entry = entries.get(itemId);
        if (entry == null || entry.expiresAt().isBefore(now)) {
            ItemSnapshot fresh = items.find(itemId).orElse(null);
            if (entries.size() > 10_000) {
                entries.clear();
            }
            entry = new Entry(fresh, now.plus(ttl));
            entries.put(itemId, entry);
        }
        return entry.snapshot();
    }

    private record Entry(ItemSnapshot snapshot, Instant expiresAt) {}
}
