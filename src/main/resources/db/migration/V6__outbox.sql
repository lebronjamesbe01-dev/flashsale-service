-- Transactional outbox: an event row is written in the same transaction as the business change,
-- then a background relay publishes it to RabbitMQ. No event is lost if the broker is down, and no
-- event exists for a change that rolled back.
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    routing_key     VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0
);
-- The relay only ever looks at unpublished rows; this index stays small.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- Consumers record every event they have applied. Delivery is at-least-once, so a redelivered
-- event finds its row here and is skipped: the effect happens exactly once.
CREATE TABLE processed_events (
    consumer      VARCHAR(50) NOT NULL,
    event_id      UUID        NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
