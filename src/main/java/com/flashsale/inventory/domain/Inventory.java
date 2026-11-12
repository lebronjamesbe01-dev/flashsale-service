package com.flashsale.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Warehouse stock of one product. {@code reserved} units are promised to flash sale quotas; {@code
 * onHand - reserved} is what can still be promised. Quantity changes go through atomic conditional
 * updates in {@link InventoryRepository}, never through setters, so concurrent requests cannot
 * over-reserve.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    protected Inventory() {}

    public static Inventory of(UUID productId, int onHand, Instant now) {
        Inventory inventory = new Inventory();
        inventory.productId = productId;
        inventory.onHand = onHand;
        inventory.reserved = 0;
        inventory.updatedAt = now;
        return inventory;
    }

    public UUID productId() {
        return productId;
    }

    public int onHand() {
        return onHand;
    }

    public int reserved() {
        return reserved;
    }

    public int available() {
        return onHand - reserved;
    }
}
