package com.flashsale.order.application;

import com.flashsale.flashsale.application.FlashSaleStockService;
import com.flashsale.flashsale.application.FlashSaleStockService.GateUnavailableException;
import com.flashsale.flashsale.application.FlashSaleStockService.PurchasableItem;
import com.flashsale.order.domain.FlashOrder;
import com.flashsale.order.domain.FlashOrderRepository;
import com.flashsale.order.domain.PurchaseLock;
import com.flashsale.outbox.application.Events;
import com.flashsale.outbox.application.OutboxWriter;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.logging.ThrottledWarning;
import com.flashsale.shared.time.BusinessClock;
import com.flashsale.wallet.application.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Buying one flash sale item.
 *
 * <pre>
 * 1. Lock (user, Idempotency-Key) in Redis        → a parallel duplicate gets PURCHASE_IN_PROGRESS
 * 2. Item on sale right now? (1 s cached)          → else FLASH_SALE_NOT_ACTIVE
 * 3. Redis gate (Lua, atomic)                      → SOLD_OUT / ALREADY_PURCHASED_TODAY, no DB hit
 * 4. One database transaction (5 s timeout):
 *      insert order    ON CONFLICT DO NOTHING      → ALREADY_PURCHASED_TODAY
 *      debit wallet    WHERE balance >= price      → INSUFFICIENT_BALANCE
 *      insert outbox   order.placed                  (inventory is synced from this event)
 *      sold = sold + 1 WHERE sold < quota          → SOLD_OUT   (last: shortest hot-row lock)
 * 5. Commit → keep the user's daily flag until midnight.
 *    Failure → give the unit back to the gate (or close the gate if the DB says sold out).
 * </pre>
 *
 * Correctness rests on the database statements in step 4; Redis only keeps hopeless requests away
 * from the database. If Redis is unreachable, steps 1, 3 and 5 are skipped and the database alone
 * decides. A retry with an Idempotency-Key that already succeeded is answered with the original
 * order.
 */
@Service
public class PurchaseService {

    /** Redis fail-open paths: one line per 30 s during an outage, not one per request. */
    private static final ThrottledWarning redisDown =
            new ThrottledWarning(
                    LoggerFactory.getLogger(PurchaseService.class), Duration.ofSeconds(30));

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final FlashOrderRepository orders;
    private final PurchaseLock lock;
    private final FlashSaleStockService stock;
    private final WalletService wallets;
    private final OutboxWriter outbox;
    private final BusinessClock clock;
    private final List<PurchaseRule> rules;
    private final TransactionTemplate transaction;

    public PurchaseService(
            FlashOrderRepository orders,
            PurchaseLock lock,
            FlashSaleStockService stock,
            WalletService wallets,
            OutboxWriter outbox,
            BusinessClock clock,
            List<PurchaseRule> rules,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.lock = lock;
        this.stock = stock;
        this.wallets = wallets;
        this.outbox = outbox;
        this.clock = clock;
        this.rules = List.copyOf(rules);
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    public PurchaseResult purchase(UUID userId, UUID itemId, String idempotencyKey) {
        boolean locked = lockOrSkip(userId, idempotencyKey);
        try {
            return purchaseOnce(userId, itemId, idempotencyKey);
        } finally {
            if (locked) {
                quietly(() -> lock.unlock(userId, idempotencyKey));
            }
        }
    }

    public List<OrderView> myOrders(UUID userId) {
        return orders.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(OrderView::of)
                .toList();
    }

    private PurchaseResult purchaseOnce(UUID userId, UUID itemId, String idempotencyKey) {
        Instant now = clock.now();
        LocalDate saleDate = clock.dateOf(now);
        PurchasableItem item = stock.requireOnSale(itemId, now);
        for (PurchaseRule rule : rules) {
            rule.check(userId, item, saleDate);
        }

        boolean admitted;
        try {
            admitted = admitOrSkip(item, userId, saleDate);
        } catch (BusinessException e) {
            if (e.code() == ErrorCode.ALREADY_PURCHASED_TODAY) {
                return replayOr(e, userId, itemId, idempotencyKey);
            }
            throw e;
        }

        PurchaseResult result;
        try {
            result =
                    transaction.execute(
                            tx -> placeOrder(userId, item, saleDate, idempotencyKey, now));
        } catch (RuntimeException e) {
            BusinessException business = e instanceof BusinessException b ? b : null;
            if (admitted) {
                undoAdmission(
                        business == null ? ErrorCode.INTERNAL_ERROR : business.code(),
                        item.itemId(),
                        userId,
                        saleDate);
            }
            if (business != null && business.code() == ErrorCode.ALREADY_PURCHASED_TODAY) {
                return replayOr(business, userId, itemId, idempotencyKey);
            }
            throw e;
        }
        if (admitted) {
            quietly(() -> stock.confirmAdmission(userId, saleDate));
        }
        return result;
    }

    private PurchaseResult placeOrder(
            UUID userId,
            PurchasableItem item,
            LocalDate saleDate,
            String idempotencyKey,
            Instant now) {
        FlashOrder order =
                FlashOrder.paid(
                        userId,
                        item.itemId(),
                        item.slotId(),
                        item.productId(),
                        saleDate,
                        item.price(),
                        idempotencyKey,
                        now);
        if (orders.insertIfFirstToday(order, idempotencyKey) == 0) {
            throw new BusinessException(ErrorCode.ALREADY_PURCHASED_TODAY);
        }
        BigDecimal balanceAfter = wallets.payForOrder(userId, item.price(), order.getId());
        outbox.write(
                "order",
                order.getId(),
                Events.ORDER_PLACED,
                new OrderPlaced(
                        order.getId(),
                        userId,
                        item.itemId(),
                        item.slotId(),
                        item.productId(),
                        item.price(),
                        saleDate,
                        now));
        stock.takeOne(item.itemId(), now);
        return PurchaseResult.created(OrderView.of(order), balanceAfter);
    }

    /**
     * "Already bought today" is also what a retry of a successful request looks like. If this
     * Idempotency-Key produced an order for the same item, answer with that order; the same key for
     * a different item is a client error.
     */
    private PurchaseResult replayOr(
            BusinessException alreadyBought, UUID userId, UUID itemId, String idempotencyKey) {
        return orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(previous -> {
                    if (!previous.itemId().equals(itemId)) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return PurchaseResult.replay(OrderView.of(previous));
                })
                .orElseThrow(() -> alreadyBought);
    }

    /** The transaction rolled back after the gate admitted us: repair the Redis state. */
    private void undoAdmission(ErrorCode reason, UUID itemId, UUID userId, LocalDate saleDate) {
        switch (reason) {
            case SOLD_OUT, FLASH_SALE_NOT_ACTIVE ->
                    quietly(() -> stock.closeGate(itemId, userId, saleDate));
            case ALREADY_PURCHASED_TODAY -> {
                // The conflict is either "user has an order today" or "this Idempotency-Key was
                // used before" (an older day). Only the database can tell which.
                Boolean boughtToday = boughtToday(userId, saleDate);
                quietly(() ->
                        stock.releaseAdmission(
                                itemId, userId, saleDate, Boolean.FALSE.equals(boughtToday)));
                if (Boolean.TRUE.equals(boughtToday)) {
                    // Flag was pending (60 s) or missing after a Redis outage: make it last.
                    quietly(() -> stock.confirmAdmission(userId, saleDate));
                }
            }
            default -> quietly(() -> stock.releaseAdmission(itemId, userId, saleDate, true));
        }
    }

    /** Null when the database cannot answer: then the short pending flag is left to expire. */
    private Boolean boughtToday(UUID userId, LocalDate saleDate) {
        try {
            return orders.existsByUserIdAndSaleDate(userId, saleDate);
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** Only a Redis failure means "no gate"; database errors from the seed read propagate. */
    private boolean admitOrSkip(PurchasableItem item, UUID userId, LocalDate saleDate) {
        try {
            stock.admit(item, userId, saleDate);
            return true;
        } catch (GateUnavailableException e) {
            redisDown.warn("Stock gate unavailable ({}), using database checks only", e.getCause());
            return false;
        }
    }

    private boolean lockOrSkip(UUID userId, String idempotencyKey) {
        boolean acquired;
        try {
            acquired = lock.tryLock(userId, idempotencyKey);
        } catch (DataAccessException redisDown) {
            return false;
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.PURCHASE_IN_PROGRESS);
        }
        return true;
    }

    /** Redis clean-up must never turn a finished purchase into an error; the gate self-heals. */
    private static void quietly(Runnable redisCall) {
        try {
            redisCall.run();
        } catch (DataAccessException e) {
            redisDown.warn(
                    "Redis clean-up skipped ({}); keys expire or are reconciled", e.getMessage());
        }
    }

    /** {@code replayed} is true when the same Idempotency-Key had already produced this order. */
    public record PurchaseResult(OrderView order, BigDecimal balanceAfter, boolean replayed) {

        static PurchaseResult created(OrderView order, BigDecimal balanceAfter) {
            return new PurchaseResult(order, balanceAfter, false);
        }

        static PurchaseResult replay(OrderView order) {
            return new PurchaseResult(order, null, true);
        }
    }
}
