package com.flashsale.outbox.infrastructure;

import com.flashsale.outbox.application.Events;
import com.flashsale.outbox.domain.EventPublisher;
import com.flashsale.outbox.domain.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes with publisher confirms and the mandatory flag, and waits for the outcome. Only a
 * positive confirm with no "returned" message counts as published, so an event is never marked sent
 * while the broker could have dropped it.
 */
@Component
class RabbitEventPublisher implements EventPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbit;

    RabbitEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
        this.rabbit.setMandatory(true);
    }

    @Override
    public void publish(OutboxMessage event) {
        Message message =
                MessageBuilder.withBody(event.payloadJson().getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(event.id().toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setHeader("event_type", event.eventType())
                        .build();
        CorrelationData correlation = new CorrelationData(event.id().toString());
        rabbit.send(Events.EXCHANGE, event.routingKey(), message, correlation);
        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("Broker nacked event: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("No queue bound for " + event.routingKey());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for confirm", e);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("No confirm for event " + event.id(), e);
        }
    }
}
