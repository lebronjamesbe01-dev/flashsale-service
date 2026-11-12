package com.flashsale.wallet.domain;

import com.flashsale.shared.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only ledger line. {@code (type, refId)} is unique, so an operation is recorded once. */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction extends AssignedIdEntity {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "ref_id", nullable = false)
    private UUID refId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletTransaction() {}

    public static WalletTransaction record(
            UUID userId,
            String type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            UUID refId,
            Instant now) {
        WalletTransaction tx = new WalletTransaction();
        tx.id = UUID.randomUUID();
        tx.userId = userId;
        tx.type = type;
        tx.amount = amount;
        tx.balanceAfter = balanceAfter;
        tx.refId = refId;
        tx.createdAt = now;
        return tx;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
