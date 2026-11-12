CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    identifier      VARCHAR(320) NOT NULL UNIQUE,
    identifier_type VARCHAR(10)  NOT NULL CHECK (identifier_type IN ('EMAIL', 'PHONE')),
    password_hash   VARCHAR(100) NOT NULL,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED')),
    role            VARCHAR(10)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Only the SHA-256 hash of a refresh token is stored.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- Mock email/SMS delivery (assignment allows log/DB/outbox).
CREATE TABLE notification_outbox (
    id          UUID         PRIMARY KEY,
    channel     VARCHAR(10)  NOT NULL CHECK (channel IN ('EMAIL', 'PHONE')),
    recipient   VARCHAR(320) NOT NULL,
    body        VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_notification_outbox_recipient ON notification_outbox (recipient, created_at DESC);
