package com.flashsale.wallet.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** Idempotent: opening an existing wallet does nothing. */
    @Modifying
    @Query(
            value =
                    "insert into wallets (user_id, balance, updated_at) values (:userId, 0, :now)"
                            + " on conflict (user_id) do nothing",
            nativeQuery = true)
    int open(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(
            "update Wallet w set w.balance = w.balance + :amount, w.updatedAt = :now,"
                    + " w.version = w.version + 1 where w.userId = :userId")
    int credit(
            @Param("userId") UUID userId,
            @Param("amount") BigDecimal amount,
            @Param("now") Instant now);

    @Query("select w.balance from Wallet w where w.userId = :userId")
    Optional<BigDecimal> findBalance(@Param("userId") UUID userId);
}
