package com.flashsale.outbox.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {

    void append(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payloadJson,
            Instant now);

    /**
     * Locks up to {@code limit} unpublished events that are due (not backing off). {@code FOR
     * UPDATE SKIP LOCKED} lets every instance run the relay at once: each one gets different rows,
     * none waits for another.
     */
    List<OutboxMessage> lockBatch(int limit, Instant now);

    void markPublished(Collection<UUID> ids, Instant now);

    /**
     * Counts a failed attempt and delays the next one (exponential, capped at 5 minutes). Returns
     * the ids whose attempt count just reached {@code parkAfter}, so the caller can raise the alarm
     * once. Rows are never dropped; they keep retrying at the capped interval.
     */
    List<UUID> markFailed(Collection<UUID> ids, Instant now, int parkAfter);

    int deletePublishedBefore(Instant cutoff);

    /** True the first time a consumer sees this event id, false on every redelivery. */
    boolean markProcessed(String consumer, UUID eventId, Instant now);

    int deleteProcessedBefore(Instant cutoff);
}
