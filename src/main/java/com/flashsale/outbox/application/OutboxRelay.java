package com.flashsale.outbox.application;

import com.flashsale.outbox.domain.EventPublisher;
import com.flashsale.outbox.domain.OutboxMessage;
import com.flashsale.outbox.domain.OutboxStore;
import com.flashsale.shared.time.BusinessClock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves events from the outbox table to RabbitMQ.
 *
 * <p>Each run locks a batch with SKIP LOCKED, publishes each event and waits for the broker's
 * confirm, then marks it published, all in one transaction. A crash after the broker confirmed but
 * before commit re-sends the event on the next run: delivery is at-least-once, and consumers
 * deduplicate by event id.
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

    private int relayBatch() {
        List<OutboxMessage> batch = store.lockBatch(BATCH);
        int published = 0;
        for (OutboxMessage message : batch) {
            try {
                publisher.publish(message);
                store.markPublished(message.id(), clock.now());
                published++;
            } catch (RuntimeException e) {
                // Stop at the first failure (broker down): keep order, retry on the next run.
                store.markFailed(message.id());
                log.warn("Outbox publish failed for {}: {}", message.id(), e.getMessage());
                break;
            }
        }
        return published;
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
