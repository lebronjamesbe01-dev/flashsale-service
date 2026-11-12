package com.flashsale.outbox.infrastructure;

import com.flashsale.outbox.application.Events;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exchanges and queues, declared at start-up (idempotent on every instance).
 *
 * <pre>
 * flashsale.events (topic)
 *   order.placed        → inventory.order-events    (warehouse stock)
 *   product.deactivated → flashsale.product-events  (pull product from upcoming slots)
 * flashsale.dlx (fanout) → flashsale.dead-letters   (messages that failed 5 times)
 * </pre>
 *
 * Quorum queues survive broker restarts and count deliveries: after 5 failed attempts a message
 * moves to the dead-letter queue instead of blocking the queue forever.
 */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfig {

    static final String DLX = "flashsale.dlx";
    static final int DELIVERY_LIMIT = 5;

    @Bean
    Declarables flashSaleTopology() {
        TopicExchange events = new TopicExchange(Events.EXCHANGE, true, false);
        FanoutExchange deadLetters = new FanoutExchange(DLX, true, false);
        Queue inventory = quorum("inventory.order-events");
        Queue products = quorum("flashsale.product-events");
        Queue dlq = QueueBuilder.durable("flashsale.dead-letters").quorum().build();
        return new Declarables(
                events,
                deadLetters,
                inventory,
                products,
                dlq,
                BindingBuilder.bind(inventory).to(events).with(Events.ORDER_PLACED),
                BindingBuilder.bind(products).to(events).with(Events.PRODUCT_DEACTIVATED),
                bind(dlq, deadLetters));
    }

    private static Queue quorum(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DLX)
                .build();
    }

    private static Binding bind(Queue queue, FanoutExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange);
    }
}
