package com.flashsale.wallet.application;

import com.flashsale.auth.application.UserActivated;
import com.flashsale.auth.application.UserDirectory;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import com.flashsale.wallet.domain.WalletRepository;
import com.flashsale.wallet.domain.WalletTransaction;
import com.flashsale.wallet.domain.WalletTransactionRepository;
import com.flashsale.wallet.domain.WalletTransactionType;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final BusinessClock clock;
    private final UserDirectory userDirectory;

    public WalletService(
            WalletRepository wallets,
            WalletTransactionRepository transactions,
            BusinessClock clock,
            UserDirectory userDirectory) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.clock = clock;
        this.userDirectory = userDirectory;
    }

    /** Every activated account gets an empty wallet, in the same transaction as the activation. */
    @EventListener
    @Transactional
    public void onUserActivated(UserActivated event) {
        wallets.open(event.userId(), clock.now());
    }

    /**
     * Admin top-up (the assignment assumes users already have balance; this is how it gets there).
     * {@code requestId} makes a retry safe: the same id credits only once and later calls just
     * return the current balance. Without one, every call is a new top-up.
     */
    @Transactional
    public BigDecimal topUp(UUID userId, BigDecimal amount, UUID requestId) {
        if (!userDirectory.isActive(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        var now = clock.now();
        wallets.open(userId, now);
        UUID refId = requestId != null ? requestId : UUID.randomUUID();
        // Ledger first: the unique (type, ref_id) decides whether this request is the first.
        if (transactions.recordTopUp(UUID.randomUUID(), userId, amount, refId, now) == 1) {
            wallets.credit(userId, amount, now);
        } else {
            // A retry is only a retry if it is the same top-up; otherwise the key was reused.
            boolean sameTopUp =
                    transactions
                            .findByTypeAndRefId(WalletTransactionType.TOP_UP, refId)
                            .map(previous -> previous.matches(userId, amount))
                            .orElse(false);
            if (!sameTopUp) {
                throw new BusinessException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different top-up");
            }
        }
        return currentBalance(userId);
    }

    /**
     * Pays for a flash sale order inside the caller's transaction. Throws INSUFFICIENT_BALANCE
     * without changing anything if the balance is too low.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal payForOrder(UUID userId, BigDecimal amount, UUID orderId) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive: " + amount);
        }
        if (wallets.debit(userId, amount, clock.now()) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        BigDecimal balance = currentBalance(userId);
        // Flushed now so the INSERT runs before the caller takes the hot flash sale row lock,
        // not at commit time while that lock is held.
        transactions.saveAndFlush(
                WalletTransaction.record(
                        userId,
                        WalletTransactionType.FLASH_PURCHASE,
                        amount.negate(),
                        balance,
                        orderId,
                        clock.now()));
        return balance;
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(UUID userId) {
        return currentBalance(userId);
    }

    private BigDecimal currentBalance(UUID userId) {
        return wallets.findBalance(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));
    }
}
