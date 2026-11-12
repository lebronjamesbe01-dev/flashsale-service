CREATE TABLE products (
    id          UUID          PRIMARY KEY,
    sku         VARCHAR(64)   NOT NULL UNIQUE,
    name        VARCHAR(200)  NOT NULL,
    list_price  NUMERIC(19, 2) NOT NULL CHECK (list_price > 0),
    status      VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0
);

-- on_hand: units physically in the warehouse.
-- reserved: units promised to flash sale quotas that are not sold yet.
-- available for new quotas = on_hand - reserved. The CHECKs make overselling the warehouse impossible.
CREATE TABLE inventory (
    product_id  UUID        PRIMARY KEY REFERENCES products (id),
    on_hand     INTEGER     NOT NULL CHECK (on_hand >= 0),
    reserved    INTEGER     NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT inventory_reserved_within_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE wallets (
    user_id     UUID           PRIMARY KEY REFERENCES users (id),
    balance     NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at  TIMESTAMPTZ    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0
);

-- Ledger of every balance change. (type, ref_id) is unique so the same top-up or purchase
-- can never be applied twice.
CREATE TABLE wallet_transactions (
    id             UUID           PRIMARY KEY,
    user_id        UUID           NOT NULL REFERENCES wallets (user_id),
    type           VARCHAR(20)    NOT NULL CHECK (type IN ('TOP_UP', 'FLASH_PURCHASE')),
    amount         NUMERIC(19, 2) NOT NULL,
    balance_after  NUMERIC(19, 2) NOT NULL,
    ref_id         UUID           NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT wallet_transactions_once UNIQUE (type, ref_id)
);
CREATE INDEX idx_wallet_transactions_user ON wallet_transactions (user_id, created_at DESC);
