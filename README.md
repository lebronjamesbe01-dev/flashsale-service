# FlashSale Service

Backend cho đăng ký / đăng nhập bằng email hoặc số điện thoại và chương trình flash sale theo khung giờ. Mục tiêu chính: **không bán quá số lượng, mỗi user tối đa 1 sản phẩm flash sale mỗi ngày, đúng khi có nhiều request đồng thời và khi chạy nhiều instance**.

Java 27 · Spring Boot 4.1 · PostgreSQL 18 · Redis 8 · RabbitMQ 4.4 · nginx · Docker Compose

Thiết kế chi tiết và sơ đồ luồng: [docs/system-design.md](docs/system-design.md)

---

## Chạy

Chỉ cần Docker.

```bash
docker compose up -d --wait
```

Lần đầu sẽ build image (vài phút). Lệnh trả về khi mọi service đã healthy: app chỉ báo sẵn sàng khi đã kết nối được Postgres, Redis và RabbitMQ, nginx chỉ nhận traffic sau đó. Flyway tự tạo bảng và nạp dữ liệu demo.

| | URL |
|---|---|
| API (qua nginx) | http://localhost:8088 |
| Swagger UI | http://localhost:8088/swagger-ui.html |
| RabbitMQ UI | http://localhost:15672 (flashsale / flashsale) |

Chạy 2 instance sau nginx:

```bash
docker compose up -d --wait --scale app=2
```

Cổng, mật khẩu, secret đều có giá trị mặc định cho môi trường demo; muốn đổi thì copy `.env.example` thành `.env`.

### Test bằng Postman

Import `postman/flashsale.postman_collection.json` (tuỳ chọn thêm `postman/flashsale-local.postman_environment.json`), chạy lần lượt từng folder hoặc chạy cả collection bằng Runner. Script tự sinh email, đọc OTP, lưu token, sinh `Idempotency-Key`.

| Folder | Nội dung |
|---|---|
| 0. Demo accounts | Đăng nhập admin / user có sẵn |
| 1. Auth | Đăng ký → đọc OTP → xác thực → đăng nhập → refresh → logout |
| 2. Flash sale (shopper) | Xem đang sale → mua → gửi lại cùng key (trả đơn cũ) → mua món thứ 2 trong ngày (bị chặn) |
| 3. Admin | Tạo sản phẩm → tạo slot → thêm sản phẩm (giữ chỗ trong kho) → publish → đóng slot (trả kho) |

### Dữ liệu demo

Profile `demo` (docker-compose bật sẵn) nạp:

- `admin@flashsale.local` / `Admin@12345` (ADMIN)
- `user0001` … `user1000@flashsale.local` / `User@12345`, mỗi ví 100.000.000đ
- 5 sản phẩm, lịch flash sale mỗi 2 giờ trong 7 ngày kể từ ngày khởi động (giờ Việt Nam), mỗi khung 3 sản phẩm, trong đó 1 sản phẩm chỉ có 5 suất để dễ thấy `SOLD_OUT`

OTP được "gửi" giả lập bằng cách ghi vào bảng `notification_outbox`. Ở profile demo có `GET /api/v1/demo/otp?identifier=...` để đọc mã vừa gửi thay cho việc mở email/SMS. **Không bật profile `demo` ở môi trường thật.**

---

## API chính

Lỗi luôn trả theo RFC 9457 (`application/problem+json`) với `code` cố định để client xử lý, ví dụ `SOLD_OUT`, `ALREADY_PURCHASED_TODAY`, `INSUFFICIENT_BALANCE`, kèm `requestId` để tra log.

**Auth** — một API cho mỗi chức năng, `identifier` là email hoặc số điện thoại (tự nhận dạng, số VN chuẩn hoá về E.164)

| | |
|---|---|
| `POST /api/v1/auth/register` | Tạo tài khoản chờ kích hoạt, gửi OTP. Luôn trả 202 cùng một câu, dù identifier đã tồn tại hay chưa |
| `POST /api/v1/auth/verify-otp` | Kích hoạt tài khoản. Sai 5 lần thì mã bị huỷ |
| `POST /api/v1/auth/resend-otp` | Gửi lại mã, cách nhau tối thiểu 60 giây |
| `POST /api/v1/auth/login` | Trả access token (JWT 15 phút) + refresh token |
| `POST /api/v1/auth/refresh` | Đổi refresh token lấy cặp token mới (refresh token chỉ dùng 1 lần) |
| `POST /api/v1/auth/logout` | Thu hồi access token ngay lập tức và refresh token |
| `GET /api/v1/auth/me` | Thông tin user hiện tại |

**Flash sale (user)**

| | |
|---|---|
| `GET /api/v1/flash-sale/current` | Khung giờ đang mở và các sản phẩm: giá sale, còn lại bao nhiêu |
| `GET /api/v1/flash-sale/slots?date=` | Lịch các khung giờ trong ngày |
| `POST /api/v1/flash-sale/items/{itemId}/purchase` | Mua. Header `Idempotency-Key` bắt buộc |
| `GET /api/v1/orders` | Đơn flash sale của tôi |
| `GET /api/v1/wallet` | Số dư |

**Admin** (role ADMIN)

| | |
|---|---|
| `/api/v1/admin/products` | Tạo (kèm tồn kho ban đầu), sửa, xem, ngừng bán sản phẩm |
| `/api/v1/admin/inventory/{productId}` | Xem tồn kho, nhập/xuất kho |
| `/api/v1/admin/flash-sale/slots` | Tạo khung giờ, thêm/sửa/xoá sản phẩm trong khung, publish, đóng |
| `/api/v1/admin/wallets/{userId}/top-up` | Nạp tiền vào ví |

Chi tiết request/response xem Swagger.

---

## Thiết kế

### Thành phần

| | Vai trò |
|---|---|
| PostgreSQL | Nguồn dữ liệu chuẩn. Các ràng buộc trong DB là chốt chặn cuối cùng cho mọi quy tắc quan trọng |
| Redis | Cổng chặn suất mua (Lua), OTP, rate limit, danh sách token đã logout, khoá idempotency. Dùng chung cho mọi instance |
| RabbitMQ | Chuyển sự kiện từ outbox sang các consumer cập nhật tồn kho |
| nginx | Chia tải giữa các instance |

App không giữ trạng thái cục bộ (ngoài cache 1 giây cho thông tin sản phẩm đang sale), nên thêm instance chỉ là `--scale app=N`.

Code chia theo module nghiệp vụ (`auth`, `wallet`, `catalog`, `inventory`, `flashsale`, `order`, `outbox`), mỗi module gồm `api → application → domain ← infrastructure`. Ranh giới giữa các module và giữa các lớp được kiểm bằng ArchUnit (`ArchitectureTest`): vi phạm thì build lỗi.

### Mua hàng và xử lý đồng thời

```
1. Khoá (user, Idempotency-Key) trên Redis           → request trùng đang chạy: PURCHASE_IN_PROGRESS
2. Sản phẩm có đang trong khung giờ?                 → FLASH_SALE_NOT_ACTIVE
3. Redis Lua (nguyên tử): đã mua hôm nay? còn suất?  → ALREADY_PURCHASED_TODAY / SOLD_OUT, không chạm DB
4. Một transaction Postgres:
     INSERT flash_orders ... ON CONFLICT DO NOTHING   unique (user_id, sale_date)
     UPDATE wallets SET balance = balance - price WHERE balance >= price
     INSERT outbox (order.placed)
     UPDATE flash_sale_items SET sold = sold + 1 WHERE sold < quota AND slot đang mở
5. Commit → cờ "đã mua hôm nay" giữ đến hết ngày. Lỗi → trả suất lại cho Redis
```

Ba lớp bảo vệ số lượng:

1. **Redis Lua** giữ phần lớn request thất bại (hết hàng, đã mua) khỏi DB. Đây là để nhanh, không phải để đúng.
2. **`sold < quota`** kiểm tra lại dưới row lock và CHECK constraint `sold <= quota`: dù Redis lệch thế nào, DB không cho bán quá.
3. **Unique `(user_id, sale_date)`**: mỗi user một đơn mỗi ngày, kể cả khi bỏ qua Redis.

Câu `sold = sold + 1` đặt cuối transaction để row lock trên dòng "nóng" giữ ngắn nhất. Không dùng `synchronized` (vô dụng khi nhiều instance) và không dùng distributed lock (không cần khi thao tác đã nguyên tử).

Các tình huống lỗi:

| Tình huống | Xử lý |
|---|---|
| Redis không truy cập được | Bỏ qua bước 1, 3, 5; DB tự quyết định. Chậm hơn nhưng vẫn đúng |
| Instance chết giữa bước 3 và commit | Cờ "đã mua" chỉ sống 60 giây khi chưa commit nên tự hết; suất bị thiếu trong Redis được job đối soát (60 giây/lần) đặt lại bằng `quota - sold` |
| Client gửi lại cùng `Idempotency-Key` | Trả lại đúng đơn cũ (200, header `Idempotent-Replayed: true`) |
| Cùng key cho sản phẩm khác | 422 `IDEMPOTENCY_KEY_REUSED` |
| Hết kết nối DB | 503 + `Retry-After` |
| Admin đóng slot đúng lúc có người mua | Đóng slot chốt `quota = sold` dưới row lock; người mua sau thấy hết hàng, không lệch tồn kho |

### Đồng bộ tồn kho

Mỗi sản phẩm có `on_hand` (trong kho) và `reserved` (đã giữ cho các quota flash sale). Thêm sản phẩm vào khung giờ sẽ giữ chỗ ngay trong cùng transaction, nên tổng quota không thể vượt tồn kho thật.

Sự kiện được ghi vào bảng `outbox` trong cùng transaction với thay đổi nghiệp vụ, một job nền (mọi instance, `FOR UPDATE SKIP LOCKED`) gửi lên RabbitMQ và chỉ đánh dấu đã gửi khi broker xác nhận. Consumer ghi `event_id` vào `processed_events` trong cùng transaction, nên sự kiện gửi lại không bị xử lý lần hai.

| Sự kiện | Consumer | Tác động |
|---|---|---|
| `order.placed` | inventory | `on_hand - 1`, `reserved - 1` |
| `product.deactivated` | flashsale | Gỡ sản phẩm khỏi các khung giờ chưa mở, trả phần đã giữ về kho |

Khung giờ kết thúc được đóng tự động, phần quota chưa bán trả về kho. Consumer lỗi được thử lại sau 1s, 3s, 9s; vẫn lỗi thì vào `flashsale.dead-letters` (xem và chuyển lại bằng RabbitMQ UI).

### Bảo mật

- Mật khẩu BCrypt (cost 12). OTP lưu dạng HMAC-SHA256, so sánh constant-time, sống 5 phút, sai 5 lần thì huỷ.
- Không lộ tài khoản: register/resend luôn trả cùng một câu; login sai mật khẩu hay không có tài khoản đều trả `INVALID_CREDENTIALS` và tốn cùng thời gian.
- Đăng ký lại khi mã OTP còn hiệu lực không đổi được mật khẩu.
- Access token JWT 15 phút; logout đưa `jti` vào danh sách thu hồi trên Redis. Refresh token lưu hash, dùng một lần; dùng lại token cũ sẽ thu hồi cả phiên đăng nhập.
- Rate limit trên Redis cho register/OTP/login/purchase. nginx ghi đè `X-Forwarded-For` để client không giả IP.
- Secret JWT/OTP không có giá trị mặc định trong code (compose chỉ cấp giá trị cho demo). Postgres/Redis/RabbitMQ chỉ mở cổng trên `127.0.0.1`.

---

## Kiểm thử

```bash
./mvnw verify    # JDK 27 + Docker: unit, integration (Testcontainers: Postgres, Redis, RabbitMQ thật), ArchUnit, format
```

Các test chính:

- 1000 user mua đồng thời 100 suất → đúng 100 đơn, 900 bị từ chối, tổng tiền trừ khớp số đơn
- Redis đếm sai (cho là còn 100000 suất) → DB vẫn chỉ bán đúng quota
- 1 user gửi 50 request vào 5 sản phẩm → đúng 1 đơn
- Gửi lại cùng `Idempotency-Key` → cùng một đơn, chỉ bán 1
- Một sự kiện gửi 3 lần → tồn kho chỉ đổi 1 lần
- Ngừng bán sản phẩm → tự rời khỏi khung giờ chưa mở, trả kho
- Đầy đủ luồng auth, chống dò tài khoản, xoay vòng refresh token

### Load test (k6)

```bash
RATE_LIMIT_ENABLED=false docker compose up -d --wait --scale app=2
./load-test/run.sh                                          # 350 req/s xem + 250 req/s mua, 60s
BROWSE_RPS=1200 PURCHASE_RPS=800 DURATION=30s ./load-test/run.sh
```

Rate limit tắt khi đo vì 300 user dùng cho test sẽ chạm giới hạn 30 lần mua/phút/user. Script chạy xong sẽ kiểm tra dữ liệu bằng `load-test/verify.sql` (không bán quá, mỗi user tối đa 1 đơn/ngày, mỗi đơn trừ tiền đúng 1 lần, outbox đã gửi hết).

Kết quả đo trên Apple M4 Max, Docker 14 CPU / 8 GB, 2 instance app:

| Tải | Thời gian | p95 | Lỗi 5xx | Vi phạm dữ liệu |
|---|---|---|---|---|
| 600 req/s (350 xem + 250 mua) | 60s | 2,9 ms | 0 | 0 |
| 2000 req/s (1200 xem + 800 mua) | 30s | 1,3 ms | 0 | 0 |

k6 báo `http_req_failed` khoảng 40% vì nó tính mọi mã ≥ 400 là lỗi; ở đây đó là các phản hồi 409 hết hàng / đã mua hôm nay, là kết quả đúng. Ngưỡng của script chỉ tính 5xx và timeout.

---

## Giả định

- Ngày tính theo giờ Việt Nam (`Asia/Ho_Chi_Minh`). Khung giờ không được vắt qua nửa đêm, nên mỗi lượt mua thuộc đúng một ngày.
- "Mỗi user 1 sản phẩm flash sale/ngày" tính trên mọi khung giờ và mọi sản phẩm trong ngày.
- Các khung giờ đã publish không được chồng nhau (exclusion constraint trong DB). Sản phẩm trong khung chỉ sửa được trước khi khung mở.
- Đăng nhập bằng identifier + mật khẩu; OTP dùng để kích hoạt tài khoản sau khi đăng ký.
- User đã có sẵn số dư (đề bài cho phép); admin có API nạp tiền để test.
- Đơn flash sale được thanh toán ngay bằng số dư ví, chưa có huỷ/hoàn đơn.
- Gửi OTP là giả lập (ghi DB + log).

## Hướng mở rộng

- **Tải lớn hơn nhiều**: nhận lượt mua vào hàng đợi rồi trả 202 + kết quả sau; chia quota của một sản phẩm thành nhiều key Redis để tránh một key quá nóng; Redis Cluster cần hash tag cho các key trong cùng script Lua.
- **Huỷ / hoàn đơn**: thêm sự kiện `order.cancelled` (cộng lại `on_hand`), hoàn tiền vào ví qua bảng giao dịch.
- **Gửi OTP thật**: một worker đọc `notification_outbox` và gọi nhà cung cấp email/SMS.
- **Báo cáo**: đẩy sự kiện từ RabbitMQ sang ClickHouse (RabbitMQ engine có sẵn) cho thống kê doanh thu, tốc độ bán hết theo khung giờ, tách khỏi DB giao dịch.
- **Điều kiện flash sale mới** (hạng thành viên, giới hạn theo khu vực…): thêm kiểm tra trước bước 3 của luồng mua; dữ liệu cấu hình đã nằm trong DB.
- **Kafka** thay RabbitMQ nếu cần đọc lại lịch sử sự kiện hoặc thông lượng rất lớn: chỉ cần viết lại `EventPublisher` và listener.

## Cấu trúc thư mục

```
src/main/java/com/flashsale/
  auth/        đăng ký, OTP, đăng nhập, JWT
  wallet/      số dư, sổ giao dịch
  catalog/     sản phẩm
  inventory/   tồn kho, consumer order.placed
  flashsale/   khung giờ, sản phẩm sale, cổng Redis, đối soát, tự đóng khung giờ
  order/       luồng mua
  outbox/      outbox, relay, RabbitMQ
  shared/      lỗi, bảo mật, thời gian, rate limit
src/main/resources/db/migration   schema (Flyway)
src/main/resources/db/demo        dữ liệu demo
postman/       collection
load-test/     k6 + kiểm tra dữ liệu sau khi đo
docs/          thiết kế hệ thống
```
