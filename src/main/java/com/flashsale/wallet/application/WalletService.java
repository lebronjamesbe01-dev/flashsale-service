package com.flashsale.wallet.application;

import com.flashsale.auth.application.UserActivated;
import com.flashsale.auth.application.UserDirectory;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.time.BusinessClock;
import com.flashsale.wallet.domain.WalletRepository;
import com.flashsale.wallet.domain.WalletTransaction;
import com.flashsale.wallet.domain.WalletTransactionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
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
     */
    @Transactional
    public BigDecimal topUp(UUID userId, BigDecimal amount) {
        if (!userDirectory.isActive(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        wallets.open(userId, clock.now());
        wallets.credit(userId, amount, clock.now());
        BigDecimal balance = balance(userId);
        transactions.save(
                WalletTransaction.record(
                        userId, "TOP_UP", amount, balance, UUID.randomUUID(), clock.now()));
        return balance;
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(UUID userId) {
        return wallets.findBalance(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));
    }
}
