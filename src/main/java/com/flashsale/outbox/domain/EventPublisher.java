package com.flashsale.outbox.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hands events to the message broker. Swapping RabbitMQ for another broker means writing another
 * adapter.
 */
public interface EventPublisher {

    /**
     * Sends the whole batch, then waits for the broker's confirms. Returns the ids the broker
     * confirmed it stored; the others (rejected or unroutable) can be retried later. Throws when
     * the broker cannot be reached at all.
     */
    Set<UUID> publish(List<OutboxMessage> batch);
}
