package com.flashsale.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Payload of the {@code order.placed} event. */
public record OrderPlaced(
        UUID orderId,
        UUID userId,
        UUID itemId,
        UUID slotId,
        UUID productId,
        BigDecimal price,
        LocalDate saleDate,
        Instant createdAt) {}
