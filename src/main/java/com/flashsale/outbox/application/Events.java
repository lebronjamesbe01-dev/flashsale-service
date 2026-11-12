package com.flashsale.outbox.application;

/**
 * Routing keys on the {@code flashsale.events} topic exchange. Producers and consumers share these
 * names; the payload is the JSON form of the record each producer passes to {@link OutboxWriter}.
 */
public final class Events {

    public static final String EXCHANGE = "flashsale.events";
    public static final String ORDER_PLACED = "order.placed";
    public static final String PRODUCT_DEACTIVATED = "product.deactivated";

    private Events() {}
}
