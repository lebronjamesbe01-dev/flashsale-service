package com.flashsale.inventory.application;

import com.flashsale.outbox.application.ProcessedEvents;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies sales to warehouse stock, exactly once per event even if RabbitMQ redelivers it. */
@Service
public class InventorySyncService {

    static final String CONSUMER = "inventory";

    private final ProcessedEvents processed;
    private final InventoryService inventory;

    public InventorySyncService(ProcessedEvents processed, InventoryService inventory) {
        this.processed = processed;
        this.inventory = inventory;
    }

    @Transactional
    public void onOrderPlaced(UUID eventId, UUID productId) {
        if (processed.firstTime(CONSUMER, eventId)) {
            inventory.shipSoldUnit(productId);
        }
    }
}
