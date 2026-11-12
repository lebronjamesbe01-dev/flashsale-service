package com.flashsale.flashsale.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class SlotCloserIntegrationTest {

    @Autowired SlotCloser closer;
    @Autowired JdbcClient jdbc;

    @Test
    void failingSlotDoesNotStopOthersAndExpiredDraftsAreClosed() {
        // Years back, so neither slot can overlap a published slot of another test
        long daysBack = 3000 + ThreadLocalRandom.current().nextInt(2000);
        Instant base = Instant.now().minus(Duration.ofDays(daysBack));
        // Poison: ends first (closed first); reserved stock does not cover its quota, so release
        // fails
        UUID poisonSlot = slot("PUBLISHED", base, base.plusSeconds(600));
        UUID poisonProduct = product(0);
        item(poisonSlot, poisonProduct, 5);
        // Expired draft that still holds reserved stock
        UUID draftSlot = slot("DRAFT", base.plusSeconds(3600), base.plusSeconds(4200));
        UUID draftProduct = product(5);
        item(draftSlot, draftProduct, 5);

        try {
            closer.closeEndedSlots();

            assertThat(status(draftSlot)).isEqualTo("CLOSED");
            assertThat(reserved(draftProduct)).isZero();
            assertThat(status(poisonSlot)).isEqualTo("PUBLISHED"); // failed and rolled back
        } finally {
            jdbc.sql("update flash_sale_slots set status = 'CLOSED' where id = ?")
                    .param(poisonSlot)
                    .update();
        }
    }

    private UUID slot(String status, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into flash_sale_slots (id, name, start_at, end_at, status,"
                                + " created_at, updated_at) values (?, 'old', ?, ?, ?, ?, ?)")
                .params(id, Timestamp.from(start), Timestamp.from(end), status, ts, ts)
                .update();
        return id;
    }

    private UUID product(int reserved) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into products (id, sku, name, list_price, status, created_at,"
                                + " updated_at) values (?, ?, 'old', 100, 'ACTIVE', ?, ?)")
                .params(id, "C-" + id, ts, ts)
                .update();
        jdbc.sql(
                        "insert into inventory (product_id, on_hand, reserved, updated_at)"
                                + " values (?, 50, ?, ?)")
                .params(id, reserved, ts)
                .update();
        return id;
    }

    private void item(UUID slotId, UUID productId, int quota) {
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into flash_sale_items (id, slot_id, product_id, sale_price, quota,"
                                + " sold, created_at, updated_at) values (?, ?, ?, 50, ?, 0, ?, ?)")
                .params(UUID.randomUUID(), slotId, productId, quota, ts, ts)
                .update();
    }

    private String status(UUID slotId) {
        return jdbc.sql("select status from flash_sale_slots where id = ?")
                .param(slotId)
                .query(String.class)
                .single();
    }

    private int reserved(UUID productId) {
        return jdbc.sql("select reserved from inventory where product_id = ?")
                .param(productId)
                .query(Integer.class)
                .single();
    }
}
