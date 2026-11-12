-- One row per successful flash sale purchase.
-- UNIQUE (user_id, sale_date) is the hard guarantee of "one flash sale product per user per day",
-- whatever happens in the Redis gate in front of it.
-- UNIQUE (user_id, idempotency_key) lets a client retry the same request and get the same order.
CREATE TABLE flash_orders (
    id               UUID           PRIMARY KEY,
    user_id          UUID           NOT NULL REFERENCES users (id),
    item_id          UUID           NOT NULL REFERENCES flash_sale_items (id),
    slot_id          UUID           NOT NULL REFERENCES flash_sale_slots (id),
    product_id       UUID           NOT NULL REFERENCES products (id),
    sale_date        DATE           NOT NULL,
    price            NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    status           VARCHAR(10)    NOT NULL CHECK (status IN ('PAID')),
    idempotency_key  VARCHAR(64)    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT flash_orders_one_per_user_per_day UNIQUE (user_id, sale_date),
    CONSTRAINT flash_orders_idempotency UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_flash_orders_item ON flash_orders (item_id);
