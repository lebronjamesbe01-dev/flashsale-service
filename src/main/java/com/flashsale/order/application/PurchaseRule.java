package com.flashsale.order.application;

import com.flashsale.flashsale.application.FlashSaleStockService.PurchasableItem;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An extra condition a shopper must meet to buy a flash sale item, e.g. membership tier, region or
 * a per-product limit. Every Spring bean implementing this interface is checked by {@link
 * PurchaseService} after the item is known to be on sale and before any stock is taken, so a new
 * business rule is a new class: the purchase flow itself does not change.
 *
 * <p>The built-in limits (stock, one purchase per user per day, wallet balance) are not rules: they
 * need atomic writes and stay inside the purchase transaction.
 */
public interface PurchaseRule {

    /**
     * Throws a {@link com.flashsale.shared.error.BusinessException} (usually {@code
     * PURCHASE_NOT_ALLOWED}) to refuse the purchase; returning normally lets it continue. Called on
     * the request thread before any lock is held, so it may read from the database or a cache.
     */
    void check(UUID userId, PurchasableItem item, LocalDate saleDate);
}
