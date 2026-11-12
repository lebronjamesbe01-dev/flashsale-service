package com.flashsale.flashsale.infrastructure;

import com.flashsale.flashsale.application.SlotAdminService;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumes {@code product.deactivated}: the product is pulled from slots that have not opened. */
@Component
class ProductEventsListener {

    private final SlotAdminService slots;
    private final JsonMapper json;

    ProductEventsListener(SlotAdminService slots, JsonMapper json) {
        this.slots = slots;
        this.json = json;
    }

    @RabbitListener(queues = "flashsale.product-events")
    void onProductDeactivated(Message message) {
        UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
        ProductDeactivatedMessage event =
                json.readValue(message.getBody(), ProductDeactivatedMessage.class);
        slots.withdrawProduct(eventId, event.productId());
    }

    record ProductDeactivatedMessage(UUID productId) {}
}
