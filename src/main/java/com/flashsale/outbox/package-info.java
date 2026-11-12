/**
 * Transactional outbox: events are stored in the same transaction as the business change, then
 * relayed to RabbitMQ by a background job. Guarantees no event is lost even if the broker is down.
 */
package com.flashsale.outbox;
