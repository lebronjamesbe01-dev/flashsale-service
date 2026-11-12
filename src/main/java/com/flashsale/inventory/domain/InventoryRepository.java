package com.flashsale.inventory.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every quantity change is a single conditional UPDATE: the WHERE clause checks the rule and the
 * row lock serialises concurrent writers. A return value of 0 means the rule would be broken.
 *
 * <p>No {@code clearAutomatically}: clearing the persistence context here would silently detach
 * entities the caller is still changing in the same transaction (e.g. a flash sale item's quota).
 */
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    @Modifying(flushAutomatically = true)
    @Query(
            "update Inventory i set i.reserved = i.reserved + :qty, i.updatedAt = :now,"
                    + " i.version = i.version + 1"
                    + " where i.productId = :productId and i.onHand - i.reserved >= :qty")
    int reserve(
            @Param("productId") UUID productId, @Param("qty") int qty, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(
            "update Inventory i set i.reserved = i.reserved - :qty, i.updatedAt = :now,"
                    + " i.version = i.version + 1"
                    + " where i.productId = :productId and i.reserved >= :qty")
    int release(
            @Param("productId") UUID productId, @Param("qty") int qty, @Param("now") Instant now);

    /** Sold flash sale units leave the warehouse: they stop being reserved and on hand. */
    @Modifying(flushAutomatically = true)
    @Query(
            "update Inventory i set i.onHand = i.onHand - :qty, i.reserved = i.reserved - :qty,"
                    + " i.updatedAt = :now, i.version = i.version + 1"
                    + " where i.productId = :productId and i.reserved >= :qty and i.onHand >= :qty")
    int shipReserved(
            @Param("productId") UUID productId, @Param("qty") int qty, @Param("now") Instant now);

    /** Stock count in/out by an admin; can never drop below what is reserved. */
    @Modifying(flushAutomatically = true)
    @Query(
            "update Inventory i set i.onHand = i.onHand + :delta, i.updatedAt = :now,"
                    + " i.version = i.version + 1"
                    + " where i.productId = :productId and i.onHand + :delta >= i.reserved")
    int adjustOnHand(
            @Param("productId") UUID productId,
            @Param("delta") int delta,
            @Param("now") Instant now);
}
