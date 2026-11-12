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
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:200ms}")
    void relay() {
        Integer published;
        do {
            published = transaction.execute(tx -> relayBatch());
        } while (published != null && published == BATCH);
    }

    /**
     * Returns how many events of the batch were stored by the broker. An event the broker rejects
     * (nack or unroutable) does not hold the others back; it is retried on later runs and its
     * {@code attempts} counter grows. Consumers do not depend on event order.
     */
    private int relayBatch() {
        List<OutboxMessage> batch = store.lockBatch(BATCH);
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
        for (OutboxMessage message : batch) {
            if (confirmed.contains(message.id())) {
                store.markPublished(message.id(), clock.now());
            } else {
                store.markFailed(message.id());
            }
        }
        return confirmed.size() == batch.size() ? batch.size() : 0;
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
