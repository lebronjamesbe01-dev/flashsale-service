# FlashSale Service

Backend service for user authentication and time-slotted flash sales, built to stay correct under concurrent load and to run as multiple stateless instances.

Java 27 · Spring Boot 4.1 · PostgreSQL 18 · Redis 8 · RabbitMQ 4.4 · nginx · Docker Compose

> Work in progress. Full documentation (APIs, assumptions, concurrency design, load test results) is added as features land.
> Architecture and flow diagrams: [docs/system-design.md](docs/system-design.md).

## Run locally

Requirements: Docker only.

```bash
docker compose up -d --wait     # builds the app on first run, returns when every service is healthy
```

The app only reports ready once it is connected to Postgres, Redis and RabbitMQ, and nginx only starts after that, so when the command returns the API is usable. Copy `.env.example` to `.env` only if you need different ports or credentials.

| What | URL |
|---|---|
| API (through nginx) | http://localhost:8088 |
| Swagger UI | http://localhost:8088/swagger-ui.html |
| Health | http://localhost:8088/actuator/health |
| RabbitMQ management | http://localhost:15672 (flashsale / flashsale) |

## Try it with Postman

Import `postman/flashsale.postman_collection.json` (and optionally `postman/flashsale-local.postman_environment.json`), then run the folders in order. Scripts generate a fresh email, read the OTP from the demo inbox and store tokens automatically.

Demo accounts (created by the `demo` profile that docker-compose enables):

| Account | Password | Role |
|---|---|---|
| admin@flashsale.local | Admin@12345 | ADMIN |
| user0001 … user1000@flashsale.local | User@12345 | USER |

OTP delivery is mocked: messages are written to the `notification_outbox` table, and `GET /api/v1/demo/otp?identifier=...` (demo profile only) returns the latest one.

Run two app instances behind nginx:

```bash
docker compose up -d --scale app=2
```

## Build and test

Requires JDK 27 and Docker (integration tests start real Postgres, Redis and RabbitMQ with Testcontainers).

```bash
./mvnw verify          # compile, tests, architecture rules, format check
./mvnw spotless:apply  # auto-format
```
