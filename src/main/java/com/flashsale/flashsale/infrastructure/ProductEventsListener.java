package com.flashsale.flashsale.infrastructure;

import com.flashsale.flashsale.application.ProductWithdrawalService;
import com.flashsale.outbox.application.EventMessageReader;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Consumes {@code product.deactivated}: the product is pulled from slots that have not opened. */
@Component
class ProductEventsListener {

    private final ProductWithdrawalService withdrawals;
    private final EventMessageReader reader;

    ProductEventsListener(ProductWithdrawalService withdrawals, EventMessageReader reader) {
        this.withdrawals = withdrawals;
        this.reader = reader;
    }

    @RabbitListener(queues = "flashsale.product-events")
    void onProductDeactivated(Message message) {
        UUID eventId = reader.eventId(message);
        ProductDeactivatedMessage event = reader.payload(message, ProductDeactivatedMessage.class);
        withdrawals.withdrawProduct(eventId, event.productId());
    }

    record ProductDeactivatedMessage(UUID productId) {}
}
