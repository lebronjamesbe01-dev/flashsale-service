package com.flashsale.order.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashOrderRepository extends JpaRepository<FlashOrder, UUID> {

    /**
     * Inserts the order unless the user already has one for that sale date (or for that idempotency
     * key). Returns 0 in that case instead of throwing, so the transaction stays usable and the
     * caller can answer with a clear business error.
     */
    @Modifying
    @Query(
            value =
                    "insert into flash_orders (id, user_id, item_id, slot_id, product_id,"
                            + " sale_date, price, status, idempotency_key, created_at)"
                            + " values (:#{#o.id}, :#{#o.userId()}, :#{#o.itemId()},"
                            + " :#{#o.slotId()}, :#{#o.productId()}, :#{#o.saleDate()},"
                            + " :#{#o.price()}, :#{#o.status()}, :key, :#{#o.createdAt()})"
                            // No conflict target on purpose: both (user, day) and (user, key) mean
                            // "not a new purchase"; the caller tells them apart by looking up the
                            // key.
                            + " on conflict do nothing",
            nativeQuery = true)
    int insertIfFirstToday(@Param("o") FlashOrder order, @Param("key") String idempotencyKey);

    Optional<FlashOrder> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    boolean existsByUserIdAndSaleDate(UUID userId, LocalDate saleDate);

    List<FlashOrder> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
}
