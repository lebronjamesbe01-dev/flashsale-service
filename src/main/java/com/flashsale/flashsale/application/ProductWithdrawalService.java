package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductService;
import com.flashsale.flashsale.domain.FlashSaleItem;
import com.flashsale.flashsale.domain.FlashSaleItemRepository;
import com.flashsale.flashsale.domain.FlashSaleSlotRepository;
import com.flashsale.inventory.application.InventoryService;
import com.flashsale.outbox.application.ProcessedEvents;
import com.flashsale.shared.time.BusinessClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reaction to {@code product.deactivated}: pulls the product out of slots that have not opened. */
@Service
public class ProductWithdrawalService {

    private final FlashSaleSlotRepository slots;
    private final FlashSaleItemRepository items;
    private final ProductService products;
    private final InventoryService inventory;
    private final ProcessedEvents processed;
    private final BusinessClock clock;

    public ProductWithdrawalService(
            FlashSaleSlotRepository slots,
            FlashSaleItemRepository items,
            ProductService products,
            InventoryService inventory,
            ProcessedEvents processed,
            BusinessClock clock) {
        this.slots = slots;
        this.items = items;
        this.products = products;
        this.inventory = inventory;
        this.processed = processed;
        this.clock = clock;
    }

    /**
     * The product leaves every slot that has not opened yet and its reserved quota goes back to the
     * warehouse. Slots already open keep selling what shoppers were promised. Idempotent: a
     * redelivered event is ignored.
     */
    @Transactional
    public void withdrawProduct(UUID eventId, UUID productId) {
        if (!processed.firstTime("flashsale-products", eventId)) {
            return;
        }
        if (products.get(productId).active()) {
            return; // reactivated before this event was processed
        }
        Instant now = clock.now();
        for (FlashSaleItem item : items.findByProductId(productId)) {
            // Same lock order as item edits and closing (slot → item → inventory): no deadlock.
            boolean upcoming =
                    slots.findForUpdateById(item.slotId())
                            .map(s -> s.isUpcoming(now))
                            .orElse(false);
            if (upcoming) {
                items.delete(item);
                inventory.release(item.productId(), item.quota());
            }
        }
    }
}
