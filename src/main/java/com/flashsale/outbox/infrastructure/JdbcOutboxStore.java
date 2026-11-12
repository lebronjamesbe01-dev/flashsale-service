package com.flashsale.outbox.infrastructure;

import com.flashsale.outbox.domain.OutboxMessage;
import com.flashsale.outbox.domain.OutboxStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;

    JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payloadJson,
            Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, routing_key,
                                            payload, created_at)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, :routingKey,
                                CAST(:payload AS jsonb), :now)
                        """)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("routingKey", routingKey)
                .param("payload", payloadJson)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public List<OutboxMessage> lockBatch(int limit, Instant now) {
        return jdbc.sql(
                        """
                        SELECT id, event_type, routing_key, payload::text AS payload
                          FROM outbox
                         WHERE published_at IS NULL
                           AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                         ORDER BY created_at
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .param("now", Timestamp.from(now))
                .query((rs, i) ->
                        new OutboxMessage(
                                rs.getObject("id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("routing_key"),
                                rs.getString("payload")))
                .list();
    }

    @Override
    public void markPublished(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql(
                        """
                        UPDATE outbox SET published_at = :now, attempts = attempts + 1
                         WHERE id IN (:ids)
                        """)
                .param("ids", ids)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public List<UUID> markFailed(Collection<UUID> ids, Instant now, int parkAfter) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // SET expressions see the old attempts value: delay = 2^attempts seconds, at most 300.
        return jdbc
                .sql(
                        """
                        UPDATE outbox
                           SET attempts = attempts + 1,
                               next_attempt_at = CAST(:now AS timestamptz)
                                   + make_interval(secs => LEAST(300, power(2, LEAST(attempts, 10))))
                         WHERE id IN (:ids)
                        RETURNING id, attempts
                        """)
                .param("ids", ids)
                .param("now", Timestamp.from(now))
                .query((rs, i) ->
                        new Attempt(rs.getObject("id", UUID.class), rs.getInt("attempts")))
                .list()
                .stream()
                .filter(attempt -> attempt.attempts() == parkAfter)
                .map(Attempt::id)
                .toList();
    }

    @Override
    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox WHERE published_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    @Override
    public boolean markProcessed(String consumer, UUID eventId, Instant now) {
        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO processed_events (consumer, event_id, processed_at)
                                VALUES (:consumer, :eventId, :now)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("consumer", consumer)
                        .param("eventId", eventId)
                        .param("now", Timestamp.from(now))
                        .update();
        return inserted == 1;
    }

    @Override
    public int deleteProcessedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM processed_events WHERE processed_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private record Attempt(UUID id, int attempts) {}
}
