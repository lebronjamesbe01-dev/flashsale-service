package com.flashsale.inventory.infrastructure;

import com.flashsale.inventory.application.InventorySyncService;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code order.placed}. Returning normally acknowledges the message after the database
 * transaction committed; throwing makes RabbitMQ redeliver it (up to the queue's delivery limit,
 * then it goes to the dead-letter queue).
 */
@Component
class OrderEventsListener {

    private final InventorySyncService sync;
    private final JsonMapper json;

    OrderEventsListener(InventorySyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    @RabbitListener(queues = "inventory.order-events")
    void onOrderPlaced(Message message) {
        UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
        OrderPlacedMessage event = json.readValue(message.getBody(), OrderPlacedMessage.class);
        sync.onOrderPlaced(eventId, event.productId());
    }

    /** Only the fields this consumer needs; other fields of the event are ignored. */
    record OrderPlacedMessage(UUID orderId, UUID productId) {}
}
