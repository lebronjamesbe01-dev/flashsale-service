package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.Inventory;
import com.flashsale.inventory.domain.InventoryRepository;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Warehouse stock operations. Callers join their own transaction (MANDATORY where it matters). */
@Service
public class InventoryService {

    /** Upper bound of on-hand stock; far below INTEGER overflow even after a max adjustment. */
    static final int MAX_ON_HAND = 1_000_000_000;

    private final InventoryRepository inventory;
    private final BusinessClock clock;

    public InventoryService(InventoryRepository inventory, BusinessClock clock) {
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID productId, int onHand) {
        inventory.save(Inventory.of(productId, onHand, clock.now()));
    }

    /** Holds stock for a flash sale quota, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID productId, int quantity) {
        if (quantity > 0 && inventory.reserve(productId, quantity, clock.now()) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY);
        }
    }

    /** Gives unsold quota back to the warehouse, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID productId, int quantity) {
        if (quantity > 0 && inventory.release(productId, quantity, clock.now()) == 0) {
            throw new IllegalStateException(
                    "Releasing more than reserved for product " + productId);
        }
    }

    /** A flash sale unit was sold: it leaves the warehouse and is no longer reserved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void shipSoldUnit(UUID productId) {
        if (inventory.shipReserved(productId, 1, clock.now()) == 0) {
            throw new IllegalStateException("No reserved stock to ship for product " + productId);
        }
    }

    @Transactional
    public InventoryView adjust(UUID productId, int delta) {
        if (delta == 0) {
            return view(productId); // nothing to change, no version bump
        }
        if (inventory.adjustOnHand(productId, delta, MAX_ON_HAND, clock.now()) == 0) {
            if (!inventory.existsById(productId)) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.INVALID_INVENTORY_ADJUSTMENT);
        }
        return view(productId);
    }

    @Transactional(readOnly = true)
    public InventoryView view(UUID productId) {
        return inventory
                .findById(productId)
                .map(InventoryView::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
