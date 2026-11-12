package com.flashsale.outbox.application;

import com.flashsale.outbox.domain.OutboxStore;
import com.flashsale.shared.time.BusinessClock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a consumer idempotent. Call it first, inside the same transaction as the consumer's own
 * changes: if the transaction rolls back, the "processed" mark rolls back with it and the event is
 * retried; if it commits, any later redelivery is ignored.
 */
@Service
public class ProcessedEvents {

    private final OutboxStore store;
    private final BusinessClock clock;

    public ProcessedEvents(OutboxStore store, BusinessClock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, UUID eventId) {
        return store.markProcessed(consumer, eventId, clock.now());
    }
}
