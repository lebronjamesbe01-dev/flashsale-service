package com.flashsale.outbox.domain;

/**
 * Hands an event to the message broker. Returns only once the broker has confirmed it stored the
 * message; throws otherwise. Swapping RabbitMQ for another broker means writing another adapter.
 */
public interface EventPublisher {

    void publish(OutboxMessage message);
}
