package com.flashsale.inventory.infrastructure;

import com.flashsale.inventory.application.InventorySyncService;
import com.flashsale.outbox.application.EventMessageReader;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code order.placed}. Returning normally acknowledges the message after the database
 * transaction committed; a malformed message is rejected at once to the dead-letter queue, any
 * other failure is retried and then dead-lettered too.
 */
@Component
class OrderEventsListener {

    private final InventorySyncService sync;
    private final EventMessageReader reader;

    OrderEventsListener(InventorySyncService sync, EventMessageReader reader) {
        this.sync = sync;
        this.reader = reader;
    }

    @RabbitListener(queues = "inventory.order-events")
    void onOrderPlaced(Message message) {
        UUID eventId = reader.eventId(message);
        OrderPlacedMessage event = reader.payload(message, OrderPlacedMessage.class);
        sync.onOrderPlaced(eventId, event.orderId(), event.productId());
    }

    /** Only the fields this consumer needs; other fields of the event are ignored. */
    record OrderPlacedMessage(UUID orderId, UUID productId) {}
}
