package com.flashsale.wallet.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Optional<WalletTransaction> findByTypeAndRefId(WalletTransactionType type, UUID refId);

    /**
     * Writes the TOP_UP ledger line once per {@code refId}; returns 0 if that top-up was already
     * recorded. The wallet row is locked first and {@code balance_after} is read from it, so the
     * line is exact under concurrency; the caller then credits the wallet only if 1 is returned. A
     * concurrent duplicate waits on the unique index until the first one commits.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "insert into wallet_transactions"
                            + " (id, user_id, type, amount, balance_after, ref_id, created_at)"
                            + " select :id, w.user_id, 'TOP_UP', :amount, w.balance + :amount,"
                            + " :refId, :now from wallets w where w.user_id = :userId for update"
                            + " on conflict (type, ref_id) do nothing",
            nativeQuery = true)
    int recordTopUp(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("amount") BigDecimal amount,
            @Param("refId") UUID refId,
            @Param("now") Instant now);
}
