package com.flashsale.outbox.domain;

import java.time.Instant;
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
     * Locks up to {@code limit} unpublished events. {@code FOR UPDATE SKIP LOCKED} lets every
     * instance run the relay at once: each one gets different rows, none waits for another.
     */
    List<OutboxMessage> lockBatch(int limit);

    void markPublished(UUID id, Instant now);

    void markFailed(UUID id);

    int deletePublishedBefore(Instant cutoff);

    /** True the first time a consumer sees this event id, false on every redelivery. */
    boolean markProcessed(String consumer, UUID eventId, Instant now);

    int deleteProcessedBefore(Instant cutoff);
}
