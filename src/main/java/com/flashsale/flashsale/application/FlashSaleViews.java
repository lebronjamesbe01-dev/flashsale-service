package com.flashsale.flashsale.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models returned by the flash sale module. */
public final class FlashSaleViews {

    private FlashSaleViews() {}

    public record ItemView(
            UUID itemId,
            UUID productId,
            String sku,
            String name,
            BigDecimal listPrice,
            BigDecimal salePrice,
            int quota,
            int sold,
            int remaining) {}

    public record SlotView(
            UUID id,
            String name,
            Instant startAt,
            Instant endAt,
            String status,
            List<ItemView> items) {}

    /** What a shopper sees: the slot open right now (null if none) and the server clock. */
    public record CurrentSaleView(Instant serverTime, SlotView slot) {}
}
