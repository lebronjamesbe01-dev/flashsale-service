package com.flashsale.outbox.application;

import com.flashsale.outbox.domain.EventPublisher;
import com.flashsale.outbox.domain.OutboxMessage;
import com.flashsale.outbox.domain.OutboxStore;
import com.flashsale.shared.time.BusinessClock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves events from the outbox table to RabbitMQ.
 *
 * <p>Each run locks a batch with SKIP LOCKED, publishes it and waits for the broker's confirms,
 * then marks the confirmed events published, all in one transaction. A crash after the broker
 * confirmed but before commit re-sends the event on the next run: delivery is at-least-once, and
 * consumers deduplicate by event id.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;
    private static final int PARK_AFTER_ATTEMPTS = 20;
    private static final int TX_TIMEOUT_SECONDS = 30;

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final BusinessClock clock;
    private final TransactionTemplate transaction;

    OutboxRelay(
            OutboxStore store,
            EventPublisher publisher,
            BusinessClock clock,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        // A blocked broker connection must not hang the relay (and hold row locks) forever.
        this.transaction.setTimeout(TX_TIMEOUT_SECONDS);
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:200ms}")
    void relay() {
        Integer handled;
        do {
            handled = transaction.execute(tx -> relayBatch());
        } while (handled != null && handled == BATCH);
    }

    /**
     * Returns the size of the batch it handled, 0 if the broker is unavailable. An event the broker
     * rejects (nack or unroutable) does not hold the others back: it backs off (see {@link
     * OutboxStore#markFailed}) and drops out of the next batches, so the loop keeps draining.
     * Consumers do not depend on event order.
     */
    private int relayBatch() {
        var now = clock.now();
        List<OutboxMessage> batch = store.lockBatch(BATCH, now);
        if (batch.isEmpty()) {
            return 0;
        }
        Set<UUID> confirmed;
        try {
            confirmed = publisher.publish(batch);
        } catch (RuntimeException brokerDown) {
            log.warn("Outbox relay paused, broker unavailable: {}", brokerDown.getMessage());
            return 0;
        }
        store.markPublished(confirmed, now);
        List<UUID> failed =
                batch.stream()
                        .map(OutboxMessage::id)
                        .filter(id -> !confirmed.contains(id))
                        .toList();
        for (UUID parked : store.markFailed(failed, now, PARK_AFTER_ATTEMPTS)) {
            log.error(
                    "Outbox event {} failed {} times, parked: still retried every 5 minutes, needs"
                            + " manual inspection (missing binding or bad routing key?)",
                    parked,
                    PARK_AFTER_ATTEMPTS);
        }
        return batch.size();
    }

    /** Published events are only kept for troubleshooting. */
    @Scheduled(cron = "${app.outbox.cleanup-cron:0 30 3 * * *}")
    void cleanUp() {
        var now = clock.now();
        int events = store.deletePublishedBefore(now.minus(Duration.ofDays(7)));
        int marks = store.deleteProcessedBefore(now.minus(Duration.ofDays(30)));
        log.info("Outbox clean-up removed {} events and {} processed marks", events, marks);
    }
}
