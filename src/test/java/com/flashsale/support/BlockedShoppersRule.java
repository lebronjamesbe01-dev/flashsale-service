package com.flashsale.support;

import com.flashsale.flashsale.application.FlashSaleStockService.PurchasableItem;
import com.flashsale.order.application.PurchaseRule;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestComponent;

/**
 * Example {@link PurchaseRule} plugged in only by registering a bean: shoppers on this list may not
 * buy. Empty by default, so other tests are unaffected.
 */
@TestComponent
public class BlockedShoppersRule implements PurchaseRule {

    private final Set<UUID> blocked = ConcurrentHashMap.newKeySet();

    public void block(UUID userId) {
        blocked.add(userId);
    }

    public void unblock(UUID userId) {
        blocked.remove(userId);
    }

    @Override
    public void check(UUID userId, PurchasableItem item, LocalDate saleDate) {
        if (blocked.contains(userId)) {
            throw new BusinessException(ErrorCode.PURCHASE_NOT_ALLOWED);
        }
    }
}
