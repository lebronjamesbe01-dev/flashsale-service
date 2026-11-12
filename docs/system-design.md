# FlashSale Service — System Design

Tài liệu mô tả kiến trúc, dữ liệu và **đường đi của từng luồng** trong hệ thống. Sơ đồ viết bằng Mermaid (GitHub tự hiển thị).

Stack: Java 27 · Spring Boot 4.1 · PostgreSQL 18 · Redis 8 · RabbitMQ 4.4 · nginx · Docker Compose. ClickHouse là hướng mở rộng (không thuộc phạm vi bản nộp).

---

## 1. Tổng quan kiến trúc

```mermaid
flowchart LR
    C["Client<br/>(Postman / Swagger / k6)"] --> N["nginx<br/>load balancer"]
    N --> A1["app instance 1<br/>Spring Boot"]
    N --> A2["app instance 2<br/>Spring Boot"]

    subgraph DATA["Hạ tầng dùng chung (mọi instance cùng thấy)"]
        PG[("PostgreSQL<br/>nguồn chân lý")]
        RD[("Redis<br/>cổng chặn suất, OTP,<br/>rate limit, token denylist")]
        MQ{{"RabbitMQ<br/>exchange flashsale.events"}}
    end

    A1 --> PG
    A1 --> RD
    A2 --> PG
    A2 --> RD
    A1 -- "outbox relay" --> MQ
    A2 -- "outbox relay" --> MQ
    MQ -- "queue inventory.events" --> A1
    MQ -- "queue inventory.events" --> A2
    MQ -.->|"mở rộng sau"| CH[("ClickHouse<br/>báo cáo")]
```

**Ý chính**
- App **không giữ state cục bộ**: mọi thứ cần chia sẻ (OTP, cờ đã mua, số suất, rate limit, token đã logout) nằm ở Redis/Postgres ⇒ thêm instance chỉ là `--scale app=N`.
- **Postgres** quyết định đúng/sai (ràng buộc cứng). **Redis** lọc tải trước khi chạm Postgres. **RabbitMQ** chuyển sự kiện để cập nhật tồn kho bất đồng bộ.
- Mỗi app instance vừa phục vụ API, vừa chạy relay outbox, vừa là consumer — không cần service riêng.

---

## 2. Module bên trong app

```mermaid
flowchart TB
    subgraph APP["flashsale-service (modular monolith)"]
        AUTH["auth<br/>register, OTP, login, logout"]
        FS["flashsale<br/>slot, item, rules, Redis gate"]
        ORD["order<br/>purchase use case"]
        WAL["wallet<br/>balance, transactions"]
        CAT["catalog<br/>product"]
        INV["inventory<br/>on_hand, reserved, consumer"]
        OB["outbox<br/>writer, relay, publisher"]
        SH["shared<br/>error, security, clock, idempotency"]
    end

    ORD --> FS
    ORD --> WAL
    ORD --> OB
    FS --> CAT
    FS --> INV
    CAT --> OB
    FS --> OB
    OB -.->|"event qua RabbitMQ"| INV
```

Mỗi module chia 4 lớp, phụ thuộc một chiều (ArchUnit kiểm):

```mermaid
flowchart LR
    API["api<br/>controller, DTO"] --> APPL["application<br/>use case"]
    APPL --> DOM["domain<br/>entity, rule, port"]
    INFRA["infrastructure<br/>JPA, JDBC, Redis, RabbitMQ"] -- "implement port" --> DOM
```

---

## 3. Dữ liệu (PostgreSQL)

```mermaid
erDiagram
    USERS ||--|| WALLETS : has
    USERS ||--o{ REFRESH_TOKENS : has
    USERS ||--o{ FLASH_ORDERS : places
    WALLETS ||--o{ WALLET_TRANSACTIONS : logs
    PRODUCTS ||--|| INVENTORY : stocked_as
    PRODUCTS ||--o{ FLASH_SALE_ITEMS : sold_in
    FLASH_SALE_SLOTS ||--o{ FLASH_SALE_ITEMS : contains
    FLASH_SALE_ITEMS ||--o{ FLASH_ORDERS : fulfils

    USERS {
        uuid id PK
        varchar identifier UK "email hoặc phone E.164"
        varchar identifier_type "EMAIL | PHONE"
        varchar password_hash "BCrypt"
        varchar status "PENDING | ACTIVE | LOCKED"
        varchar role "USER | ADMIN"
    }
    WALLETS {
        uuid user_id PK
        numeric balance "check >= 0"
        bigint version
    }
    WALLET_TRANSACTIONS {
        uuid id PK
        uuid user_id FK
        numeric amount
        varchar type "TOP_UP | FLASH_PURCHASE"
        uuid ref_id "unique cùng type"
    }
    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK
        uuid family_id
        timestamptz expires_at
        timestamptz revoked_at
    }
    PRODUCTS {
        uuid id PK
        varchar sku UK
        varchar name
        numeric list_price
        varchar status "ACTIVE | INACTIVE"
    }
    INVENTORY {
        uuid product_id PK
        int on_hand "check >= 0"
        int reserved "check 0..on_hand"
    }
    FLASH_SALE_SLOTS {
        uuid id PK
        timestamptz start_at
        timestamptz end_at "không chồng giờ"
        varchar status "DRAFT | PUBLISHED | CLOSED"
    }
    FLASH_SALE_ITEMS {
        uuid id PK
        uuid slot_id FK
        uuid product_id FK
        numeric sale_price
        int quota "check > 0"
        int sold "check 0..quota"
    }
    FLASH_ORDERS {
        uuid id PK
        uuid user_id FK
        uuid item_id FK
        date sale_date "unique cùng user_id"
        numeric price
        varchar status
    }
```

Bảng hạ tầng: `outbox` (sự kiện chờ gửi), `processed_events` (sự kiện đã xử lý, chống trùng), `notification_outbox` (mock gửi OTP).

**Ràng buộc cứng — tuyến phòng thủ cuối cùng**

| Ràng buộc | Chặn lỗi gì |
|---|---|
| `flash_sale_items.sold <= quota` (check) + `UPDATE ... WHERE sold < quota` | Bán quá số lượng |
| `unique (flash_orders.user_id, sale_date)` | User mua 2 sản phẩm flash sale trong 1 ngày |
| `wallets.balance >= 0` + `UPDATE ... WHERE balance >= price` | Trừ tiền âm |
| Exclusion constraint trên `tstzrange(start_at, end_at)` | Hai khung giờ chồng nhau |
| `inventory.reserved <= on_hand` | Cấp quota vượt tồn kho thật |
| `processed_events (consumer, event_id)` PK | Xử lý trùng sự kiện tồn kho |

---

## 4. Redis — key nào, để làm gì

| Key | Giá trị | TTL | Dùng cho |
|---|---|---|---|
| `fs:stock:{itemId}` | số suất còn lại | đến hết slot | Cổng chặn: hết suất thì từ chối ngay |
| `fs:bought:{yyyyMMdd}:{userId}` | itemId đã mua | hết ngày + 1h | Cổng chặn: hôm nay mua rồi |
| `otp:{type}:{identifier}` | hash OTP + số lần sai | 5 phút | Xác thực OTP |
| `otp:cooldown:{identifier}` | 1 | 60 giây | Chặn gửi lại OTP liên tục |
| `jwt:deny:{jti}` | 1 | thời gian còn lại của token | Token đã logout |
| `idem:{userId}:{key}` | kết quả request | 24h | Chống bấm mua 2 lần |
| `rl:{scope}:{ip/identifier}` | bucket | theo cửa sổ | Rate limit |
| `fs:current` | JSON danh sách đang sale | 2 giây | Cache API xem danh sách |

---

## 5. Luồng đăng ký + OTP

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant API as AuthController
    participant S as RegisterUseCase
    participant DB as Postgres
    participant R as Redis

    U->>API: POST /auth/register {identifier, password}
    API->>API: Validate, nhận dạng email hay phone, chuẩn hoá
    API->>R: Rate limit theo IP + identifier
    API->>S: register()
    S->>DB: Tìm user theo identifier
    alt Đã ACTIVE
        S-->>API: Không làm gì (không lộ là đã tồn tại)
    else Mới hoặc còn PENDING
        S->>DB: Upsert user PENDING + BCrypt(password)
        S->>R: Lưu HMAC(OTP), attempts=0, TTL 5 phút
        S->>DB: Ghi notification_outbox (mock gửi, log OTP ở profile dev)
    end
    API-->>U: 202 "Nếu thông tin hợp lệ, OTP đã được gửi"

    U->>API: POST /auth/verify-otp {identifier, otp}
    API->>R: Lấy OTP, so sánh constant-time
    alt Đúng
        API->>DB: status = ACTIVE, tạo wallet balance 0
        API->>R: Xoá OTP
        API-->>U: 200 Đã kích hoạt
    else Sai
        API->>R: attempts + 1 (đủ 5 lần thì xoá OTP)
        API-->>U: 400 OTP_INVALID
    end
```

## 6. Luồng đăng nhập, gọi API, đăng xuất

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant API as App
    participant DB as Postgres
    participant R as Redis

    U->>API: POST /auth/login {identifier, password}
    API->>DB: Tìm user
    API->>API: BCrypt verify (user không tồn tại vẫn verify hash giả để chống đo thời gian)
    alt Sai hoặc chưa ACTIVE
        API-->>U: 401 INVALID_CREDENTIALS (một thông báo chung)
    else Đúng
        API->>DB: Lưu hash refresh token
        API-->>U: accessToken (JWT 15 phút, có jti) + refreshToken
    end

    U->>API: GET /flash-sale/current (Bearer token)
    API->>API: Kiểm chữ ký + hạn JWT
    API->>R: jti có trong denylist không?
    API-->>U: 200 dữ liệu

    U->>API: POST /auth/logout
    API->>R: SET jwt:deny:{jti} TTL = thời gian còn lại
    API->>DB: Thu hồi refresh token
    API-->>U: 204
```

---

## 7. Luồng mua flash sale (trọng tâm)

### 7.1 Đường đi khi thành công

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant C as PurchaseController
    participant UC as PurchaseUseCase
    participant RL as Rules
    participant R as Redis
    participant DB as Postgres

    U->>C: POST /flash-sale/items/{id}/purchase<br/>Idempotency-Key: abc
    C->>R: SET NX idem:{user}:abc
    C->>UC: purchase(user, item)
    UC->>RL: SlotActive, ItemInActiveSlot, UserActive
    UC->>R: Lua try_acquire(item, user, date)
    Note over R: Nguyên tử, không ai chen ngang:<br/>đã mua hôm nay? còn suất?<br/>nếu ổn: DECR stock, SET cờ đã mua
    R-->>UC: OK
    UC->>DB: BEGIN
    UC->>DB: UPDATE items SET sold=sold+1 WHERE id=? AND sold < quota
    UC->>DB: UPDATE wallets SET balance=balance-price WHERE user=? AND balance>=price
    UC->>DB: INSERT flash_orders (unique user_id + sale_date)
    UC->>DB: INSERT wallet_transactions
    UC->>DB: INSERT outbox (order.placed)
    UC->>DB: COMMIT
    UC-->>C: orderId, balanceAfter
    C->>R: Lưu kết quả vào idem:{user}:abc
    C-->>U: 201 Created
```

### 7.2 Mọi nhánh rẽ (thất bại và bù trừ)

```mermaid
flowchart TD
    S([Request mua]) --> I{"Idempotency-Key<br/>đã xử lý?"}
    I -- "có" --> IR["Trả lại kết quả cũ"]
    I -- "chưa" --> RULE{"Đang trong khung giờ?<br/>Item thuộc slot đang mở?"}
    RULE -- "không" --> E1["400 SLOT_NOT_ACTIVE"]
    RULE -- "có" --> LUA{"Redis Lua"}
    LUA -- "đã mua hôm nay" --> E2["409 ALREADY_PURCHASED_TODAY"]
    LUA -- "hết suất" --> E3["409 SOLD_OUT"]
    LUA -- "OK, đã giữ 1 suất" --> TX["Transaction Postgres"]
    TX -- "sold < quota sai" --> RB1["Rollback"] --> COMP["Lua release:<br/>trả 1 suất, xoá cờ đã mua"] --> E3b["409 SOLD_OUT"]
    TX -- "không đủ tiền" --> RB2["Rollback"] --> COMP2["Lua release"] --> E4["402 INSUFFICIENT_BALANCE"]
    TX -- "trùng unique user+ngày" --> RB3["Rollback"] --> E2b["409 ALREADY_PURCHASED_TODAY"]
    TX -- "commit" --> OK["201 Created + event order.placed vào outbox"]
```

### 7.3 Vì sao không bao giờ bán quá

```mermaid
flowchart LR
    REQ["10.000 request<br/>tranh 100 suất"] --> G1["Lớp 1 — Redis Lua<br/>chỉ ~100 request lọt qua"]
    G1 --> G2["Lớp 2 — Postgres<br/>UPDATE ... WHERE sold < quota<br/>+ check constraint"]
    G2 --> G3["Lớp 3 — unique index<br/>user_id + sale_date"]
    G3 --> OK["Đúng 100 đơn<br/>mỗi user tối đa 1 đơn/ngày"]
```

- Lớp 1 để **nhanh**: 9.900 request bị từ chối trong RAM, Postgres không phải gánh.
- Lớp 2, 3 để **đúng**: kể cả Redis lệch số (app chết giữa chừng, Redis restart), Postgres vẫn từ chối phần vượt.
- Không dùng `synchronized` hay lock trong JVM — vô dụng khi chạy nhiều instance. Không dùng distributed lock — chậm và không cần, vì thao tác nguyên tử đã đủ.

### 7.4 Sự cố và cách xử lý

| Sự cố | Hệ quả | Xử lý |
|---|---|---|
| App chết sau khi Redis đã giữ suất, trước khi commit DB | Redis thiếu 1 suất (bán thiếu, **không** bán thừa) | `StockReconciler` định kỳ đặt lại `fs:stock = quota - sold` |
| Redis chết | Không có cổng chặn | Chuyển sang chỉ dùng Postgres (chậm hơn nhưng vẫn đúng) |
| Client bấm mua 2 lần / mạng retry | Có thể tạo 2 request | `Idempotency-Key` trả lại kết quả lần đầu |
| Qua nửa đêm giữa khung giờ | Lệch `sale_date` | Admin không được tạo slot vắt qua 00:00 |

---

## 8. Đồng bộ tồn kho (outbox → RabbitMQ → consumer)

### 8.1 Đường đi của một sự kiện

```mermaid
sequenceDiagram
    autonumber
    participant UC as PurchaseUseCase
    participant DB as Postgres
    participant RL as OutboxRelay (mọi instance)
    participant MQ as RabbitMQ
    participant L as InventoryListener

    UC->>DB: Cùng transaction với đơn hàng:<br/>INSERT outbox(order.placed)
    loop Mỗi ~200ms
        RL->>DB: SELECT outbox chưa gửi<br/>FOR UPDATE SKIP LOCKED LIMIT 100
        RL->>MQ: Publish (persistent, message_id = event_id)
        MQ-->>RL: Publisher confirm (đã nhận)
        RL->>DB: published_at = now()
    end
    MQ->>L: Giao tin từ queue inventory.events
    L->>DB: BEGIN
    L->>DB: INSERT processed_events(consumer, event_id)<br/>ON CONFLICT DO NOTHING
    alt Đã xử lý trước đó (0 row)
        L->>DB: COMMIT, bỏ qua
    else Lần đầu
        L->>DB: UPDATE inventory SET on_hand=on_hand-1, reserved=reserved-1
        L->>DB: COMMIT
    end
    L-->>MQ: ACK
```

### 8.2 Topology RabbitMQ

```mermaid
flowchart LR
    P["OutboxRelay"] --> EX{{"exchange flashsale.events<br/>(topic)"}}
    EX -- "order.* slot.* product.*" --> Q["queue inventory.events<br/>(quorum, delivery-limit 5)"]
    Q --> L["InventoryListener"]
    Q -- "lỗi quá 5 lần" --> DLX{{"exchange flashsale.dlx"}}
    DLX --> DLQ["queue inventory.events.dlq<br/>(soi tay)"]
```

### 8.3 Sự kiện và tác động

| Sự kiện | Khi nào | Tồn kho thay đổi |
|---|---|---|
| `order.placed` | Mua thành công | `on_hand - 1`, `reserved - 1` |
| `slot.closed` | Hết khung giờ / admin đóng | `reserved - (quota - sold)` (trả suất chưa bán về kho) |
| `product.deactivated` | Admin ngừng bán | Gỡ khỏi slot chưa bắt đầu, trả `reserved` |
| `inventory.adjusted` | Admin nhập/xuất kho | Ghi nhận (kiểm `on_hand >= reserved` làm ngay lúc admin sửa) |

**Vì sao "không trùng, nhất quán"**: outbox ghi cùng transaction ⇒ không mất sự kiện. Relay có thể gửi lại (ít nhất 1 lần) ⇒ consumer chống trùng bằng `processed_events` ⇒ kết quả **đúng 1 lần**.

---

## 9. Vòng đời

```mermaid
stateDiagram-v2
    direction LR
    state "Khung giờ flash sale" as SLOT {
        [*] --> DRAFT: admin tạo
        DRAFT --> PUBLISHED: publish (nạp stock vào Redis)
        PUBLISHED --> CLOSED: hết giờ / admin đóng (event slot.closed)
        CLOSED --> [*]
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Tài khoản" as ACC {
        [*] --> PENDING: register
        PENDING --> ACTIVE: verify OTP đúng
        ACTIVE --> LOCKED: admin khoá
        LOCKED --> ACTIVE: admin mở
    }
```

---

## 10. Triển khai local (Docker Compose)

```mermaid
flowchart LR
    subgraph COMPOSE["docker compose up"]
        NG["nginx :8080"] --> APP["app ×N<br/>(--scale app=2)"]
        APP --> PG[("postgres:18.6")]
        APP --> RD[("redis:8.8")]
        APP --> MQ{{"rabbitmq:4.4<br/>UI :15672"}}
    end
    K6["k6 (docker)"] -.->|"load test"| NG
```

Flyway tự tạo bảng + seed dữ liệu dev (admin, user test có tiền, sản phẩm, khung giờ trong ngày) khi app khởi động.
