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
    MQ -- "queue inventory.order-events<br/>flashsale.product-events" --> A1
    MQ -- "queue inventory.order-events<br/>flashsale.product-events" --> A2
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
        varchar status "PENDING | ACTIVE | LOCKED (chưa có API khoá)"
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
        uuid slot_id FK
        uuid product_id FK
        date sale_date "unique cùng user_id"
        numeric price
        varchar idempotency_key "unique cùng user_id"
        varchar status
    }
```

Bảng hạ tầng: `outbox` (sự kiện chờ gửi; gửi lỗi thì lùi lịch theo `next_attempt_at`, tối đa 5 phút/lần), `processed_events` (sự kiện đã xử lý, chống trùng), `notification_outbox` (mock gửi OTP), `refresh_tokens` (hash refresh token).

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
| `fs:stock:{itemId}` | số suất còn lại cổng cho qua | đến hết slot + 1h | Hết suất thì từ chối ngay, không chạm DB |
| `fs:bought:{yyyy-MM-dd}:{userId}` | itemId đã mua | 60s khi đang mua, kéo đến hết ngày sau khi commit | Hôm nay đã mua thì từ chối ngay |
| `purchase:lock:{userId}:{key}` | 1 | 30s | Request trùng `Idempotency-Key` đang chạy song song |
| `otp:{identifier}` | hash `{code: HMAC, attempts}` | 5 phút | Xác thực OTP |
| `otp:cooldown:{identifier}` | 1 | 60 giây | Chặn gửi lại OTP liên tục |
| `jwt:deny:{jti}` | 1 | thời gian còn lại của token | Token đã logout |
| `rl:{rule}:{ip/identifier/user}` | bộ đếm | theo cửa sổ | Rate limit |

Redis mất dữ liệu cũng không làm sai: số suất và quyền mua được DB quyết định, các key trên chỉ để lọc tải và tự được đối soát lại.

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
    alt Đã ACTIVE, hoặc PENDING mà OTP cũ còn hiệu lực
        S-->>API: Không làm gì (không lộ là đã tồn tại; mật khẩu cũ giữ nguyên)
    else Mới, hoặc PENDING và OTP cũ đã hết hạn
        S->>DB: INSERT user PENDING ON CONFLICT DO NOTHING (chỉ khi user mới)
        S->>R: Lưu HMAC(OTP), attempts=0, TTL 5 phút
        S->>DB: Ghi notification_outbox (mock gửi, log OTP ở profile dev)
    end
    API-->>U: 202 "Nếu thông tin hợp lệ, OTP đã được gửi"

    U->>API: POST /auth/verify-otp {identifier, otp, password}
    API->>R: Lấy OTP, so sánh constant-time
    alt Đúng
        API->>DB: status = ACTIVE + password_hash = BCrypt(password)<br/>(chỉ khi đang PENDING), mở ví số dư 0
        Note over API,DB: Mật khẩu đặt ở bước này: chỉ người đọc được OTP mới đặt được,<br/>nên đăng ký trước bằng identifier của người khác không chiếm được tài khoản
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
    participant P as PurchaseService
    participant R as Redis
    participant DB as Postgres

    U->>C: POST /flash-sale/items/{id}/purchase<br/>Idempotency-Key: abc
    C->>R: Rate limit theo user
    C->>P: purchase(user, item, abc)
    P->>R: SET NX purchase:lock:{user}:abc (30s)
    P->>P: Item đang trong khung giờ? (cache 1s)
    P->>P: Các PurchaseRule (điều kiện mua mở rộng, mặc định không có)
    P->>R: Lua admit(item, user, ngày)
    Note over R: Nguyên tử: đã mua hôm nay? còn suất?<br/>nếu ổn: DECR stock, SET cờ đã mua (60s)
    R-->>P: ADMITTED
    P->>DB: BEGIN (timeout 5s)
    P->>DB: INSERT flash_orders ... ON CONFLICT DO NOTHING
    P->>DB: UPDATE wallets SET balance = balance - price WHERE balance >= price
    P->>DB: INSERT wallet_transactions, INSERT outbox (order.placed)
    P->>DB: UPDATE flash_sale_items SET sold = sold + 1 WHERE sold < quota AND slot đang mở
    P->>DB: COMMIT
    P->>R: Kéo cờ đã mua đến hết ngày, mở khoá
    C-->>U: 201 Created {order, balanceAfter}
```

Câu `sold = sold + 1` để cuối cùng: dòng của sản phẩm đang sale là dòng bị tranh chấp nhiều nhất, khoá nó càng muộn thì thời gian giữ khoá càng ngắn.

### 7.2 Mọi nhánh rẽ

```mermaid
flowchart TD
    S([Request mua]) --> K{"Có Idempotency-Key hợp lệ?"}
    K -- "không" --> E0["400 IDEMPOTENCY_KEY_REQUIRED"]
    K -- "có" --> L{"Khoá (user, key)"}
    L -- "đang có request khác giữ" --> E1["409 PURCHASE_IN_PROGRESS"]
    L -- "được" --> A{"Khung giờ đang mở?"}
    A -- "không" --> E2["409 FLASH_SALE_NOT_ACTIVE"]
    A -- "có" --> LUA{"Redis Lua"}
    LUA -- "hết suất" --> E3["409 SOLD_OUT"]
    LUA -- "đã mua hôm nay" --> RP{"Key này đã tạo đơn?"}
    RP -- "cùng sản phẩm" --> OK2["200 trả lại đơn cũ"]
    RP -- "khác sản phẩm" --> E5["422 IDEMPOTENCY_KEY_REUSED"]
    RP -- "không" --> E4["409 ALREADY_PURCHASED_TODAY"]
    LUA -- "cho qua" --> TX["Transaction Postgres"]
    TX -- "trùng (user, ngày)" --> RP
    TX -- "không đủ tiền" --> R1["Rollback, trả suất cho Redis"] --> E6["409 INSUFFICIENT_BALANCE"]
    TX -- "sold < quota sai" --> R2["Rollback, đặt counter Redis = 0"] --> E3
    TX -- "hết kết nối / quá 5s" --> E7["503 SERVICE_BUSY"]
    TX -- "commit" --> OK["201 Created"]
```

### 7.3 Vì sao không bao giờ bán quá

```mermaid
flowchart LR
    REQ["10.000 request<br/>tranh 100 suất"] --> G1["Lớp 1 — Redis Lua<br/>chỉ ~100 request lọt qua"]
    G1 --> G2["Lớp 2 — Postgres<br/>sold < quota dưới row lock<br/>+ CHECK sold <= quota"]
    G2 --> G3["Lớp 3 — unique<br/>user_id + sale_date"]
    G3 --> OK["Đúng 100 đơn<br/>mỗi user tối đa 1 đơn/ngày"]
```

- Lớp 1 để **nhanh**: request hết hy vọng bị từ chối trong RAM, Postgres không phải gánh.
- Lớp 2, 3 để **đúng**: Redis lệch số thế nào (instance chết giữa chừng, Redis restart, Redis chết hẳn), Postgres vẫn từ chối phần vượt. Có test cố tình đặt counter Redis = 100000 để chứng minh.
- Không dùng `synchronized` hay lock trong JVM — vô dụng khi chạy nhiều instance. Không dùng distributed lock — các câu lệnh đã nguyên tử.

### 7.4 Sự cố và cách xử lý

| Sự cố | Hệ quả | Xử lý |
|---|---|---|
| Instance chết sau khi Redis cho qua, trước khi commit | Redis thiếu 1 suất; user có cờ "đã mua" nhưng không có đơn | Cờ chỉ sống 60s nếu chưa commit. `StockReconciler` (60s/lần, mọi instance) ghi đè `fs:stock = quota - sold` |
| Redis chết | Không có cổng chặn, không đọc được token denylist | Lettuce từ chối lệnh ngay khi mất kết nối (không chờ timeout); bỏ qua khoá, cổng, rate limit và denylist (fail open); DB tự quyết, vẫn đúng. Token đã logout còn dùng được tới khi hết hạn (≤ 15 phút). Readiness chỉ kiểm DB nên instance không bị gỡ khỏi load balancer |
| RabbitMQ chết | Sự kiện chưa gửi được | Đơn vẫn tạo; sự kiện chờ trong `outbox`, relay gửi lại khi broker sống lại; tồn kho cập nhật trễ, không mất |
| Redis đếm cao hơn thực tế | Thêm request vào tới DB | DB từ chối, counter Redis được đặt về 0 ngay |
| Bấm mua 2 lần / client retry | 2 request cùng key | Khoá Redis + unique `(user_id, idempotency_key)`; lần sau nhận lại đơn cũ |
| DB quá tải | Hết kết nối trong 3s | 503 + `Retry-After: 1` thay vì 500 |
| Admin đóng slot giữa lúc có người mua | Có thể trả kho sai | Đóng slot = `UPDATE ... SET quota = sold RETURNING old.quota - old.sold` dưới row lock; ai mua sau đó thấy hết hàng |
| Qua nửa đêm | Lệch `sale_date` | Khung giờ không được vắt qua 00:00; ngày được chốt một lần mỗi request |

---

## 8. Đồng bộ tồn kho (outbox → RabbitMQ → consumer)

### 8.1 Đường đi của một sự kiện

```mermaid
sequenceDiagram
    autonumber
    participant UC as PurchaseService
    participant DB as Postgres
    participant RL as OutboxRelay (mọi instance)
    participant MQ as RabbitMQ
    participant L as Inventory consumer

    UC->>DB: Cùng transaction với đơn hàng:<br/>INSERT outbox(order.placed)
    loop Mỗi ~200ms
        RL->>DB: SELECT outbox chưa gửi<br/>FOR UPDATE SKIP LOCKED LIMIT 100
        RL->>MQ: Gửi cả lô (persistent, message_id = event_id)
        MQ-->>RL: Publisher confirms
        RL->>DB: published_at = now() cho các sự kiện được xác nhận
    end
    MQ->>L: Giao tin từ queue inventory.order-events
    L->>DB: BEGIN
    L->>DB: INSERT processed_events(consumer, event_id)<br/>ON CONFLICT DO NOTHING
    alt Đã xử lý trước đó (0 row)
        L->>DB: COMMIT, bỏ qua
    else Lần đầu
        L->>DB: UPDATE inventory SET on_hand = on_hand - 1, reserved = reserved - 1
        L->>DB: COMMIT
    end
    L-->>MQ: ACK
```

### 8.2 Topology RabbitMQ

```mermaid
flowchart LR
    P["OutboxRelay"] --> EX{{"exchange flashsale.events<br/>(topic)"}}
    EX -- "order.placed" --> Q1["inventory.order-events<br/>(quorum)"]
    EX -- "product.deactivated" --> Q2["flashsale.product-events<br/>(quorum)"]
    Q1 --> L1["Inventory consumer"]
    Q2 --> L2["Flash sale consumer"]
    Q1 -- "hết lượt thử / tin hỏng" --> DLX{{"flashsale.dlx"}}
    Q2 -- "hết lượt thử / tin hỏng" --> DLX
    DLX --> DLQ["flashsale.dead-letters"]
```

Consumer lỗi được thử lại trong ứng dụng sau 1s, 3s, 9s (đủ để qua một lần DB khởi động lại); vẫn lỗi thì tin bị từ chối và chuyển sang `flashsale.dead-letters` để xem và chuyển lại thủ công. Tin hỏng (thiếu `message_id`, JSON sai) bị từ chối ngay, không thử lại.

Phía gửi: sự kiện bị broker nack hoặc không route được không chặn cả lô; `attempts + 1`, `next_attempt_at = now + min(2^attempts, 300) s`, relay chỉ lấy dòng đã tới hạn. Quá 20 lần thì log ERROR một lần (vẫn giữ dòng để xử lý tay).

### 8.3 Sự kiện và tác động

| Sự kiện | Khi nào | Tồn kho thay đổi |
|---|---|---|
| (đồng bộ) thêm sản phẩm vào khung giờ | Admin | `reserved + quota`, từ chối nếu `on_hand - reserved < quota` |
| (đồng bộ) đóng khung giờ | Admin hoặc job tự đóng khi hết giờ | `reserved - (quota - sold)`, chốt `quota = sold` |
| `order.placed` | Mua thành công | `on_hand - 1`, `reserved - 1` |
| `product.deactivated` | Admin ngừng bán sản phẩm | Gỡ khỏi các khung giờ chưa mở, `reserved - quota` |

Tổng cho mỗi khung giờ: giữ `Q` → bán `S` (trả qua sự kiện) → đóng (trả `Q - S`) → `reserved` về 0, thứ tự nào cũng ra cùng kết quả.

**Vì sao "không trùng, nhất quán"**: outbox ghi cùng transaction ⇒ không mất và không thừa sự kiện. Relay gửi ít nhất 1 lần ⇒ consumer chống trùng bằng `processed_events` trong chính transaction của nó ⇒ tác động xảy ra **đúng 1 lần**.

---

## 9. Vòng đời

```mermaid
stateDiagram-v2
    direction LR
    state "Khung giờ flash sale" as SLOT {
        [*] --> DRAFT: admin tạo
        DRAFT --> PUBLISHED: publish (không được chồng giờ slot khác)
        PUBLISHED --> CLOSED: hết giờ (job tự đóng) / admin đóng, trả kho phần chưa bán
        DRAFT --> CLOSED: admin huỷ bản nháp / job tự đóng khi bản nháp đã hết giờ (trả kho)
        CLOSED --> [*]
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Tài khoản" as ACC {
        [*] --> PENDING: register
        PENDING --> ACTIVE: verify OTP đúng
        ACTIVE --> LOCKED: khoá tay trong DB (chưa có API)
    }
```

---

## 10. Triển khai local (Docker Compose)

```mermaid
flowchart LR
    subgraph COMPOSE["docker compose up"]
        NG["nginx :8088"] --> APP["app ×N<br/>(--scale app=2)"]
        APP --> PG[("postgres:18.6")]
        APP --> RD[("redis:8.8")]
        APP --> MQ{{"rabbitmq:4.4<br/>UI :15672"}}
    end
    K6["k6 (docker)"] -.->|"load test"| NG
```

Flyway tự tạo bảng + seed dữ liệu dev (admin, user test có tiền, sản phẩm, khung giờ mỗi 2 tiếng trong 7 ngày) khi app khởi động (chỉ ở profile demo).
