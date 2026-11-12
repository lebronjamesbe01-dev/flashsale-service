package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.Inventory;
import java.util.UUID;

public record InventoryView(UUID productId, int onHand, int reserved, int available) {

    static InventoryView of(Inventory inventory) {
        return new InventoryView(
                inventory.productId(),
                inventory.onHand(),
                inventory.reserved(),
                inventory.available());
    }
}
