package com.flashsale.wallet.domain;

/** Kinds of ledger line; the names are stored in {@code wallet_transactions.type}. */
public enum WalletTransactionType {
    TOP_UP,
    FLASH_PURCHASE
}
