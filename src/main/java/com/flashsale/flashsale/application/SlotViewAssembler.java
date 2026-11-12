package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductService;
import com.flashsale.catalog.application.ProductView;
import com.flashsale.flashsale.application.FlashSaleViews.ItemView;
import com.flashsale.flashsale.application.FlashSaleViews.SlotView;
import com.flashsale.flashsale.domain.FlashSaleItem;
import com.flashsale.flashsale.domain.FlashSaleItemRepository;
import com.flashsale.flashsale.domain.FlashSaleSlot;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds slot views with their items and product details using two batched queries. */
@Component
class SlotViewAssembler {

    private final FlashSaleItemRepository items;
    private final ProductService products;

    SlotViewAssembler(FlashSaleItemRepository items, ProductService products) {
        this.items = items;
        this.products = products;
    }

    SlotView one(FlashSaleSlot slot) {
        return all(List.of(slot)).getFirst();
    }

    List<SlotView> all(Collection<FlashSaleSlot> slots) {
        if (slots.isEmpty()) {
            return List.of();
        }
        List<UUID> slotIds = slots.stream().map(FlashSaleSlot::id).toList();
        Map<UUID, List<FlashSaleItem>> itemsBySlot =
                items.findBySlotIdIn(slotIds).stream()
                        .collect(Collectors.groupingBy(FlashSaleItem::slotId));
        Map<UUID, ProductView> productById =
                products.getAll(
                        itemsBySlot.values().stream()
                                .flatMap(List::stream)
                                .map(FlashSaleItem::productId)
                                .collect(Collectors.toSet()));
        return slots.stream()
                .map(slot ->
                        new SlotView(
                                slot.id(),
                                slot.name(),
                                slot.startAt(),
                                slot.endAt(),
                                slot.status().name(),
                                itemsBySlot.getOrDefault(slot.id(), List.of()).stream()
                                        .map(i -> item(i, productById.get(i.productId())))
                                        .toList()))
                .toList();
    }

    private static ItemView item(FlashSaleItem item, ProductView product) {
        return new ItemView(
                item.id(),
                item.productId(),
                product.sku(),
                product.name(),
                product.listPrice(),
                item.salePrice(),
                item.quota(),
                item.sold(),
                item.remaining());
    }
}
