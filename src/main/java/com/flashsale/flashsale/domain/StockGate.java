package com.flashsale.flashsale.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fast in-memory admission control in front of the database. It turns away requests that cannot
 * succeed (item sold out, user already bought today) without touching Postgres. It is an
 * optimisation, not the source of truth: the database constraints still decide.
 */
public interface StockGate {

    /**
     * Takes one unit and sets the user's daily flag with a short {@code pendingTtl}. If the
     * purchase never commits (crash, timeout) the flag simply expires instead of blocking the user
     * for the rest of the day.
     */
    Admission tryAdmit(UUID itemId, UUID userId, LocalDate saleDate, Duration pendingTtl);

    /** After commit: keep the daily flag until the end of the sale day. */
    void confirm(UUID userId, LocalDate saleDate, Duration dailyFlagTtl);

    /** Puts the unit back and, unless the user really bought today, clears their daily flag. */
    void release(UUID itemId, UUID userId, LocalDate saleDate, boolean clearDailyFlag);

    /** The database says nothing is left: stop admitting anyone for this item. */
    void markSoldOut(UUID itemId, UUID userId, LocalDate saleDate);

    /** Creates the counter if missing. Returns false when another request created it first. */
    boolean initialize(UUID itemId, int remaining, Duration ttl);

    /** Overwrites the counter with the database value (used by the periodic reconciler). */
    void overwrite(UUID itemId, int remaining, Duration ttl);

    enum Admission {
        ADMITTED,
        SOLD_OUT,
        ALREADY_PURCHASED_TODAY,
        NOT_INITIALIZED
    }
}
