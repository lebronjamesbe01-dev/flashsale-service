package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductService;
import com.flashsale.catalog.application.ProductView;
import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import com.flashsale.flashsale.domain.FlashSaleItem;
import com.flashsale.flashsale.domain.FlashSaleItemRepository;
import com.flashsale.flashsale.domain.FlashSaleItemStore;
import com.flashsale.flashsale.domain.FlashSaleSlot;
import com.flashsale.flashsale.domain.FlashSaleSlotRepository;
import com.flashsale.flashsale.domain.SlotStatus;
import com.flashsale.inventory.application.InventoryService;
import com.flashsale.outbox.application.ProcessedEvents;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin configuration of flash sale slots and their items. Every quota is backed by stock reserved
 * in the warehouse at the moment it is configured, so the sum of quotas can never exceed real
 * stock.
 */
@Service
public class SlotAdminService {

    private final FlashSaleSlotRepository slots;
    private final FlashSaleItemRepository items;
    private final FlashSaleItemStore itemStore;
    private final ProductService products;
    private final InventoryService inventory;
    private final SlotViewAssembler assembler;
    private final BusinessClock clock;
    private final ProcessedEvents processed;

    public SlotAdminService(
            FlashSaleSlotRepository slots,
            FlashSaleItemRepository items,
            FlashSaleItemStore itemStore,
            ProductService products,
            InventoryService inventory,
            SlotViewAssembler assembler,
            BusinessClock clock,
            ProcessedEvents processed) {
        this.slots = slots;
        this.items = items;
        this.itemStore = itemStore;
        this.products = products;
        this.inventory = inventory;
        this.assembler = assembler;
        this.clock = clock;
        this.processed = processed;
    }

    @Transactional
    public SlotView create(String name, Instant startAt, Instant endAt) {
        FlashSaleSlot slot = FlashSaleSlot.draft(name, startAt, endAt, clock.zone(), clock.now());
        return assembler.one(slots.save(slot));
    }

    @Transactional
    public SlotView reschedule(UUID slotId, String name, Instant startAt, Instant endAt) {
        FlashSaleSlot slot = load(slotId);
        if (slot.status() != SlotStatus.DRAFT) {
            throw new BusinessException(ErrorCode.SLOT_NOT_EDITABLE);
        }
        slot.reschedule(name, startAt, endAt, clock.zone(), clock.now());
        return assembler.one(slot);
    }

    /** DRAFT → PUBLISHED. The database rejects a slot overlapping another live slot. */
    @Transactional
    public SlotView publish(UUID slotId) {
        FlashSaleSlot slot = load(slotId);
        if (slot.status() != SlotStatus.DRAFT || !slot.endAt().isAfter(clock.now())) {
            throw new BusinessException(ErrorCode.SLOT_NOT_EDITABLE);
        }
        slot.publish(clock.now());
        try {
            slots.saveAndFlush(slot);
        } catch (DataIntegrityViolationException overlap) {
            throw new BusinessException(ErrorCode.SLOT_OVERLAP);
        }
        return assembler.one(slot);
    }

    /**
     * Ends a slot (or cancels a draft) and gives the unsold part of every quota back to the
     * warehouse. Quotas are frozen at {@code sold} under row locks, so a purchase racing with the
     * close either completes before it (and is not released) or finds the item sold out.
     */
    @Transactional
    public SlotView close(UUID slotId) {
        FlashSaleSlot slot = load(slotId);
        if (slot.status() == SlotStatus.CLOSED) {
            return assembler.one(slot);
        }
        for (FlashSaleItemStore.ReleasedQuota released :
                itemStore.freezeSlot(slotId, clock.now())) {
            inventory.release(released.productId(), released.quantity());
        }
        slot.close(clock.now());
        return assembler.one(slot);
    }

    @Transactional
    public SlotView addItem(UUID slotId, UUID productId, BigDecimal salePrice, int quota) {
        FlashSaleSlot slot = editable(slotId);
        ProductView product = products.getLocked(productId);
        if (!product.active()) {
            throw new BusinessException(ErrorCode.PRODUCT_INACTIVE);
        }
        if (items.existsBySlotIdAndProductId(slotId, productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_ALREADY_IN_SLOT);
        }
        inventory.reserve(productId, quota);
        items.save(FlashSaleItem.create(slotId, productId, salePrice, quota, clock.now()));
        return assembler.one(slot);
    }

    @Transactional
    public SlotView changeItem(UUID slotId, UUID itemId, BigDecimal salePrice, int quota) {
        FlashSaleSlot slot = editable(slotId);
        FlashSaleItem item = loadItem(slotId, itemId);
        int delta = quota - item.quota();
        if (delta > 0) {
            inventory.reserve(item.productId(), delta);
        } else if (delta < 0) {
            inventory.release(item.productId(), -delta);
        }
        item.change(salePrice, quota, clock.now());
        return assembler.one(slot);
    }

    @Transactional
    public SlotView removeItem(UUID slotId, UUID itemId) {
        FlashSaleSlot slot = editable(slotId);
        FlashSaleItem item = loadItem(slotId, itemId);
        inventory.release(item.productId(), item.quota());
        items.delete(item);
        return assembler.one(slot);
    }

    /**
     * Reaction to {@code product.deactivated}: the product leaves every slot that has not opened
     * yet and its reserved quota goes back to the warehouse. Slots already open keep selling what
     * shoppers were promised. Idempotent: a redelivered event is ignored.
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
            boolean editable =
                    slots.findById(item.slotId()).map(s -> s.isEditable(now)).orElse(false);
            if (editable) {
                inventory.release(item.productId(), item.quota());
                items.delete(item);
            }
        }
    }

    @Transactional(readOnly = true)
    public SlotView get(UUID slotId) {
        return assembler.one(load(slotId));
    }

    @Transactional(readOnly = true)
    public List<SlotView> listByDay(LocalDate day) {
        LocalDate date = day == null ? clock.today() : day;
        Instant from = date.atStartOfDay(clock.zone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(clock.zone()).toInstant();
        return assembler.all(slots.findStartingBetween(from, to, EnumSet.allOf(SlotStatus.class)));
    }

    private FlashSaleSlot editable(UUID slotId) {
        FlashSaleSlot slot = load(slotId);
        if (!slot.isEditable(clock.now())) {
            throw new BusinessException(ErrorCode.SLOT_NOT_EDITABLE);
        }
        return slot;
    }

    private FlashSaleSlot load(UUID slotId) {
        return slots.findById(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_NOT_FOUND));
    }

    private FlashSaleItem loadItem(UUID slotId, UUID itemId) {
        return items.findById(itemId)
                .filter(i -> i.slotId().equals(slotId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_ITEM_NOT_FOUND));
    }
}
