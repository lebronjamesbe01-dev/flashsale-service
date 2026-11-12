package com.flashsale.wallet.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {}
