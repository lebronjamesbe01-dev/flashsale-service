package com.flashsale.order.application;

import com.flashsale.order.domain.FlashOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OrderView(
        UUID orderId,
        UUID itemId,
        UUID slotId,
        UUID productId,
        BigDecimal price,
        LocalDate saleDate,
        String status,
        Instant createdAt) {

    static OrderView of(FlashOrder order) {
        return new OrderView(
                order.getId(),
                order.itemId(),
                order.slotId(),
                order.productId(),
                order.price(),
                order.saleDate(),
                order.status(),
                order.createdAt());
    }
}
