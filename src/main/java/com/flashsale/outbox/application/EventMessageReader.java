package com.flashsale.outbox.application;

import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads an event off a RabbitMQ message for the consumers. A message without an id or with an
 * unreadable body will never succeed on retry, so it is rejected at once (straight to the
 * dead-letter queue) instead of being retried.
 */
@Component
public class EventMessageReader {

    private final JsonMapper json;

    EventMessageReader(JsonMapper json) {
        this.json = json;
    }

    /** The outbox event id, which the publisher sets as the message id. */
    public UUID eventId(Message message) {
        String id = message.getMessageProperties().getMessageId();
        if (id == null) {
            throw new AmqpRejectAndDontRequeueException("Event message has no messageId");
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Event message has bad messageId " + id, e);
        }
    }

    public <T> T payload(Message message, Class<T> type) {
        try {
            return json.readValue(message.getBody(), type);
        } catch (JacksonException e) {
            throw new AmqpRejectAndDontRequeueException(
                    "Unreadable " + type.getSimpleName() + " in event " + eventId(message), e);
        }
    }
}
