package com.flashsale.order.domain;

import java.util.UUID;

/**
 * Short-lived lock per (user, idempotency key) shared by all instances. A double click or a client
 * retry that arrives while the first request is still running is told to wait instead of racing it.
 */
public interface PurchaseLock {

    boolean tryLock(UUID userId, String idempotencyKey);

    void unlock(UUID userId, String idempotencyKey);
}
