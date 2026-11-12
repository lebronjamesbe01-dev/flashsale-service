package com.flashsale.flashsale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A product offered in a slot: sale price and a fixed quota. {@code sold} is only ever incremented
 * by the purchase flow with a conditional update ({@code sold < quota}), never through this class.
 */
@Entity
@Table(name = "flash_sale_items")
public class FlashSaleItem {

    @Id private UUID id;

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sale_price", nullable = false)
    private BigDecimal salePrice;

    @Column(nullable = false)
    private int quota;

    @Column(nullable = false)
    private int sold;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    protected FlashSaleItem() {}

    public static FlashSaleItem create(
            UUID slotId, UUID productId, BigDecimal salePrice, int quota, Instant now) {
        FlashSaleItem item = new FlashSaleItem();
        item.id = UUID.randomUUID();
        item.slotId = slotId;
        item.productId = productId;
        item.salePrice = salePrice;
        item.quota = quota;
        item.sold = 0;
        item.createdAt = now;
        item.updatedAt = now;
        return item;
    }

    public void change(BigDecimal salePrice, int quota, Instant now) {
        this.salePrice = salePrice;
        this.quota = quota;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID slotId() {
        return slotId;
    }

    public UUID productId() {
        return productId;
    }

    public BigDecimal salePrice() {
        return salePrice;
    }

    public int quota() {
        return quota;
    }

    public int sold() {
        return sold;
    }

    public int remaining() {
        return quota - sold;
    }
}
