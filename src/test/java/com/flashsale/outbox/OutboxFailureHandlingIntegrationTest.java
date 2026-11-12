package com.flashsale.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.outbox.application.Events;
import com.flashsale.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A bad event must neither be retried in a hot loop nor wait through useless consumer retries. */
@IntegrationTest
class OutboxFailureHandlingIntegrationTest {

    private static final String DEAD_LETTERS = "flashsale.dead-letters";

    @Autowired JdbcClient jdbc;
    @Autowired RabbitTemplate rabbit;

    @Test
    void anUnroutableEventBacksOffInsteadOfBeingRetriedEveryTick() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type,
                                            routing_key, payload, created_at)
                        VALUES (?, 'Test', ?, 'Poison', 'no.binding.for.this.key', '{}'::jsonb, ?)
                        """)
                .params(id, UUID.randomUUID(), Timestamp.from(Instant.now()))
                .update();
        try {
            awaitTrue(() -> attempts(id) >= 1);
            Thread.sleep(3000);

            // Relay ticks every 200ms: a hot loop would be near 15 attempts by now. With 1s, 2s...
            // backoff there are about 3.
            assertThat(attempts(id)).isBetween(2, 5);
            assertThat(
                            jdbc.sql(
                                            "select next_attempt_at is not null from outbox"
                                                    + " where id = ? and published_at is null")
                                    .param(id)
                                    .query(Boolean.class)
                                    .single())
                    .isTrue();
        } finally {
            jdbc.sql("delete from outbox where id = ?").param(id).update();
        }
    }

    @Test
    void aMessageWithoutIdGoesStraightToTheDeadLetterQueue() throws Exception {
        String marker = "no-id-" + UUID.randomUUID();
        rabbit.send(Events.EXCHANGE, Events.ORDER_PLACED, plain(marker, null));

        assertThat(awaitDeadLetter(marker)).as("dead-lettered within 6s, no 13s retry").isTrue();
    }

    @Test
    void aMessageWithBadJsonGoesStraightToTheDeadLetterQueue() throws Exception {
        String marker = "bad-json-" + UUID.randomUUID();
        rabbit.send(
                Events.EXCHANGE,
                Events.PRODUCT_DEACTIVATED,
                plain(marker, UUID.randomUUID().toString()));

        assertThat(awaitDeadLetter(marker)).as("dead-lettered within 6s, no 13s retry").isTrue();
    }

    private static Message plain(String body, String messageId) {
        var builder =
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json");
        if (messageId != null) {
            builder.setMessageId(messageId);
        }
        return builder.build();
    }

    private boolean awaitDeadLetter(String marker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < deadline) {
            Message dead = rabbit.receive(DEAD_LETTERS, 500);
            if (dead != null && new String(dead.getBody(), StandardCharsets.UTF_8).equals(marker)) {
                return true;
            }
        }
        return false;
    }

    private int attempts(UUID id) {
        return jdbc.sql("select attempts from outbox where id = ?")
                .param(id)
                .query(Integer.class)
                .single();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("condition reached within 10s").isTrue();
    }
}
