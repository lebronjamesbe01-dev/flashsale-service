-- Demo accounts, loaded only with the "demo" profile (docker-compose enables it).
-- admin@flashsale.local / Admin@12345            role ADMIN
-- user0001..user1000@flashsale.local / User@12345 role USER (1000 users for load tests)
INSERT INTO users (id, identifier, identifier_type, password_hash, status, role, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin@flashsale.local', 'EMAIL',
        '$2a$12$Fj4pO3xwS3SIhU1/k3GQ3OHiUqifm7fhYbnuj85HU1F5ZQT0ucfbW', 'ACTIVE', 'ADMIN', now(), now())
ON CONFLICT (identifier) DO NOTHING;

INSERT INTO users (id, identifier, identifier_type, password_hash, status, role, created_at, updated_at)
SELECT ('00000000-0000-0000-0001-' || lpad(n::text, 12, '0'))::uuid,
       'user' || lpad(n::text, 4, '0') || '@flashsale.local',
       'EMAIL',
       '$2a$12$X9uTkb3I/FzVpv9xwxFX7eTzOVGn/EdhlyldzBGmFmq1omROJI1bW',
       'ACTIVE', 'USER', now(), now()
FROM generate_series(1, 1000) AS n
ON CONFLICT (identifier) DO NOTHING;
