package com.flashsale.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only mapping; balance changes go through atomic updates in {@link WalletRepository}. */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private BigDecimal balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version private Long version;

    protected Wallet() {}

    public UUID userId() {
        return userId;
    }

    public BigDecimal balance() {
        return balance;
    }
}
