package com.flashsale.outbox.infrastructure;

import com.flashsale.outbox.application.Events;
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
 * flashsale.dlx (fanout) → flashsale.dead-letters   (messages the consumers gave up on)
 * </pre>
 *
 * Quorum queues survive broker restarts. A listener that keeps failing is retried in-process (see
 * {@code spring.rabbitmq.listener.simple.retry}), then rejects the message without requeue and the
 * broker dead-letters it on that first delivery. The delivery limit is the safety net for
 * redeliveries (consumer crash, dropped connection): after {@value #DELIVERY_LIMIT} deliveries the
 * message is dead-lettered instead of looping forever.
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
                BindingBuilder.bind(dlq).to(deadLetters));
    }

    private static Queue quorum(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DLX)
                .build();
    }
}
