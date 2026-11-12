package com.flashsale.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Direct SQL set-up for purchase tests: many active users with money, and a slot open right now.
 * Going through the admin API for 1000 users would only slow the tests down.
 */
@Component
public class FlashSaleFixtures {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcClient jdbc;

    public FlashSaleFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> usersWithBalance(int count, BigDecimal balance) {
        List<UUID> ids = new ArrayList<>(count);
        Timestamp now = Timestamp.from(Instant.now());
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            jdbc.sql(
                            "insert into users (id, identifier, identifier_type, password_hash,"
                                    + " status, role, created_at, updated_at)"
                                    + " values (?, ?, 'EMAIL', 'x', 'ACTIVE', 'USER', ?, ?)")
                    .params(id, "load-" + id + "@test.local", now, now)
                    .update();
            jdbc.sql("insert into wallets (user_id, balance, updated_at) values (?, ?, ?)")
                    .params(id, balance, now)
                    .update();
        }
        return ids;
    }

    /** A published slot open right now (clamped to today) with one item per given quota. */
    public OpenSlot openSlotWithItems(BigDecimal price, int... quotas) {
        ZonedDateTime now = ZonedDateTime.now(VN);
        LocalDate today = now.toLocalDate();
        ZonedDateTime start = max(now.minusMinutes(5), today.atStartOfDay(VN));
        ZonedDateTime end = min(now.plusMinutes(30), today.plusDays(1).atStartOfDay(VN));
        UUID slotId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into flash_sale_slots (id, name, start_at, end_at, status,"
                                + " created_at, updated_at) values (?, 'test', ?, ?, 'PUBLISHED',"
                                + " ?, ?)")
                .params(
                        slotId,
                        Timestamp.from(start.toInstant()),
                        Timestamp.from(end.toInstant()),
                        ts,
                        ts)
                .update();
        List<UUID> itemIds = new ArrayList<>();
        for (int quota : quotas) {
            UUID productId = UUID.randomUUID();
            jdbc.sql(
                            "insert into products (id, sku, name, list_price, status, created_at,"
                                    + " updated_at) values (?, ?, 'test', ?, 'ACTIVE', ?, ?)")
                    .params(productId, "T-" + productId, price.multiply(BigDecimal.TWO), ts, ts)
                    .update();
            jdbc.sql(
                            "insert into inventory (product_id, on_hand, reserved, updated_at)"
                                    + " values (?, ?, ?, ?)")
                    .params(productId, quota + 10, quota, ts)
                    .update();
            UUID itemId = UUID.randomUUID();
            jdbc.sql(
                            "insert into flash_sale_items (id, slot_id, product_id, sale_price,"
                                    + " quota, sold, created_at, updated_at)"
                                    + " values (?, ?, ?, ?, ?, 0, ?, ?)")
                    .params(itemId, slotId, productId, price, quota, ts, ts)
                    .update();
            itemIds.add(itemId);
        }
        return new OpenSlot(slotId, itemIds);
    }

    /** Takes the slot out of the "no overlapping published slots" constraint for the next test. */
    public void close(OpenSlot slot) {
        jdbc.sql("update flash_sale_slots set status = 'CLOSED' where id = ?")
                .param(slot.slotId())
                .update();
    }

    public int sold(UUID itemId) {
        return jdbc.sql("select sold from flash_sale_items where id = ?")
                .param(itemId)
                .query(Integer.class)
                .single();
    }

    public int orderCount(UUID itemId) {
        return jdbc.sql("select count(*) from flash_orders where item_id = ?")
                .param(itemId)
                .query(Integer.class)
                .single();
    }

    public int usersWithMoreThanOneOrderToday(List<UUID> userIds) {
        return jdbc.sql(
                        "select count(*) from (select user_id from flash_orders where user_id ="
                                + " any(?) group by user_id, sale_date having count(*) > 1) t")
                .param(userIds.toArray(UUID[]::new))
                .query(Integer.class)
                .single();
    }

    public BigDecimal totalBalance(List<UUID> userIds) {
        return jdbc.sql("select coalesce(sum(balance), 0) from wallets where user_id = any(?)")
                .param(userIds.toArray(UUID[]::new))
                .query(BigDecimal.class)
                .single();
    }

    public UUID productOf(UUID itemId) {
        return jdbc.sql("select product_id from flash_sale_items where id = ?")
                .param(itemId)
                .query(UUID.class)
                .single();
    }

    /** {onHand, reserved} of a product's warehouse stock. */
    public int[] inventory(UUID productId) {
        return jdbc.sql("select on_hand, reserved from inventory where product_id = ?")
                .param(productId)
                .query((rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)})
                .single();
    }

    public int itemsOfProduct(UUID productId) {
        return jdbc.sql("select count(*) from flash_sale_items where product_id = ?")
                .param(productId)
                .query(Integer.class)
                .single();
    }

    /** A draft slot far in the future holding one item of {@code quota}, with stock reserved. */
    public UUID upcomingItem(int quota) {
        UUID slotId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        Instant start =
                Instant.now().plus(java.time.Duration.ofDays(3650 + (long) (Math.random() * 1000)));
        jdbc.sql(
                        "insert into flash_sale_slots (id, name, start_at, end_at, status,"
                                + " created_at, updated_at) values (?, 'future', ?, ?, 'DRAFT', ?, ?)")
                .params(
                        slotId,
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(600)),
                        ts,
                        ts)
                .update();
        UUID productId = UUID.randomUUID();
        jdbc.sql(
                        "insert into products (id, sku, name, list_price, status, created_at,"
                                + " updated_at) values (?, ?, 'future', 100, 'ACTIVE', ?, ?)")
                .params(productId, "F-" + productId, ts, ts)
                .update();
        jdbc.sql(
                        "insert into inventory (product_id, on_hand, reserved, updated_at)"
                                + " values (?, ?, ?, ?)")
                .params(productId, quota + 10, quota, ts)
                .update();
        jdbc.sql(
                        "insert into flash_sale_items (id, slot_id, product_id, sale_price, quota,"
                                + " sold, created_at, updated_at) values (?, ?, ?, 50, ?, 0, ?, ?)")
                .params(UUID.randomUUID(), slotId, productId, quota, ts, ts)
                .update();
        return productId;
    }

    private static ZonedDateTime max(ZonedDateTime a, ZonedDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static ZonedDateTime min(ZonedDateTime a, ZonedDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    public record OpenSlot(UUID slotId, List<UUID> itemIds) {

        public UUID item(int index) {
            return itemIds.get(index);
        }
    }
}
