CREATE TABLE flash_sale_slots (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    start_at    TIMESTAMPTZ  NOT NULL,
    end_at      TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT flash_sale_slots_time_order CHECK (end_at > start_at),
    -- Two published slots can never overlap in time. Drafts and closed slots do not block a window.
    CONSTRAINT flash_sale_slots_no_overlap
        EXCLUDE USING gist (tstzrange(start_at, end_at, '[)') WITH &&) WHERE (status = 'PUBLISHED')
);
CREATE INDEX idx_flash_sale_slots_live ON flash_sale_slots (status, start_at);

-- sold can never exceed quota: the last line of defence against overselling.
-- Closing a slot freezes the offer by setting quota = sold, hence quota may be 0.
CREATE TABLE flash_sale_items (
    id          UUID           PRIMARY KEY,
    slot_id     UUID           NOT NULL REFERENCES flash_sale_slots (id) ON DELETE CASCADE,
    product_id  UUID           NOT NULL REFERENCES products (id),
    sale_price  NUMERIC(19, 2) NOT NULL CHECK (sale_price > 0),
    quota       INTEGER        NOT NULL CHECK (quota >= 0),
    sold        INTEGER        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT flash_sale_items_sold_within_quota CHECK (sold >= 0 AND sold <= quota),
    CONSTRAINT flash_sale_items_one_per_slot UNIQUE (slot_id, product_id)
);
