-- A failed publish (broker nack, unroutable) backs off instead of being retried every relay tick.
-- NULL means "due now".
ALTER TABLE outbox ADD COLUMN next_attempt_at TIMESTAMPTZ;

-- The relay reads unpublished rows in creation order and filters on next_attempt_at; carrying the
-- column in the index lets the filter run without visiting the table row.
DROP INDEX idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) INCLUDE (next_attempt_at)
    WHERE published_at IS NULL;
