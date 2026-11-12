package com.flashsale.outbox.domain;

import java.util.UUID;

/** An event waiting in the outbox (or being relayed). {@code id} doubles as the message id. */
public record OutboxMessage(UUID id, String eventType, String routingKey, String payloadJson) {}
