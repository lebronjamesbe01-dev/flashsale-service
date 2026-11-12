package com.flashsale.flashsale.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashSaleItemRepository extends JpaRepository<FlashSaleItem, UUID> {

    List<FlashSaleItem> findBySlotIdOrderByCreatedAt(UUID slotId);

    List<FlashSaleItem> findBySlotIdIn(Collection<UUID> slotIds);

    boolean existsBySlotIdAndProductId(UUID slotId, UUID productId);

    List<FlashSaleItem> findByProductId(UUID productId);
}
