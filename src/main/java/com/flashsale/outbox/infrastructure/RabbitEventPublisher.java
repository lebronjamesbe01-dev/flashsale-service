package com.flashsale.outbox.infrastructure;

import com.flashsale.outbox.application.Events;
import com.flashsale.outbox.domain.EventPublisher;
import com.flashsale.outbox.domain.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes with publisher confirms and the mandatory flag ({@code spring.rabbitmq.template}). The
 * batch is sent first and the confirms are collected afterwards, so a batch costs about one broker
 * round-trip instead of one per event. Only a positive confirm with no "returned" message counts as
 * stored.
 */
@Component
class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitTemplate rabbit;

    RabbitEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public Set<UUID> publish(List<OutboxMessage> batch) {
        List<Pending> pending = new ArrayList<>(batch.size());
        for (OutboxMessage event : batch) {
            CorrelationData correlation = new CorrelationData(event.id().toString());
            rabbit.send(Events.EXCHANGE, event.routingKey(), toMessage(event), correlation);
            pending.add(new Pending(event, correlation));
        }
        Set<UUID> confirmed = new HashSet<>();
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MILLIS;
        for (Pending p : pending) {
            if (stored(p, Math.max(1, deadline - System.currentTimeMillis()))) {
                confirmed.add(p.event().id());
            }
        }
        return confirmed;
    }

    private static boolean stored(Pending p, long waitMillis) {
        try {
            CorrelationData.Confirm confirm =
                    p.correlation().getFuture().get(waitMillis, TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                log.warn("Broker nacked event {}: {}", p.event().id(), confirm.reason());
                return false;
            }
            if (p.correlation().getReturned() != null) {
                log.error("Event {} is unroutable ({})", p.event().id(), p.event().routingKey());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private static Message toMessage(OutboxMessage event) {
        return MessageBuilder.withBody(event.payloadJson().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(event.id().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("event_type", event.eventType())
                .build();
    }

    private record Pending(OutboxMessage event, CorrelationData correlation) {}
}
