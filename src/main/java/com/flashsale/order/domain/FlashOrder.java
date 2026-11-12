package com.flashsale.order.domain;

import com.flashsale.shared.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "flash_orders")
public class FlashOrder extends AssignedIdEntity {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FlashOrder() {}

    public static FlashOrder paid(
            UUID userId,
            UUID itemId,
            UUID slotId,
            UUID productId,
            LocalDate saleDate,
            BigDecimal price,
            String idempotencyKey,
            Instant now) {
        FlashOrder order = new FlashOrder();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.itemId = itemId;
        order.slotId = slotId;
        order.productId = productId;
        order.saleDate = saleDate;
        order.price = price;
        order.status = "PAID";
        order.idempotencyKey = idempotencyKey;
        order.createdAt = now;
        return order;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID itemId() {
        return itemId;
    }

    public UUID slotId() {
        return slotId;
    }

    public UUID productId() {
        return productId;
    }

    public LocalDate saleDate() {
        return saleDate;
    }

    public BigDecimal price() {
        return price;
    }

    public String status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
