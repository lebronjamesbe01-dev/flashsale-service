package com.flashsale.flashsale.infrastructure;

import com.flashsale.flashsale.domain.FlashSaleItemStore;
import com.flashsale.flashsale.domain.SlotStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcFlashSaleItemStore implements FlashSaleItemStore {

    private static final String SNAPSHOT =
            """
            SELECT i.id, i.slot_id, i.product_id, i.sale_price, i.quota - i.sold AS remaining,
                   s.status, s.start_at, s.end_at
              FROM flash_sale_items i
              JOIN flash_sale_slots s ON s.id = i.slot_id
            """;

    private final JdbcClient jdbc;

    JdbcFlashSaleItemStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReleasedQuota> freezeSlot(UUID slotId, Instant now) {
        // PostgreSQL 18 RETURNING old.*: the quota before the freeze, read under the row lock.
        return jdbc.sql(
                        """
                        UPDATE flash_sale_items
                           SET quota = sold, updated_at = :now, version = version + 1
                         WHERE slot_id = :slotId AND quota > sold
                        RETURNING product_id, old.quota - old.sold AS released
                        """)
                .param("slotId", slotId)
                .param("now", Timestamp.from(now))
                .query((rs, i) ->
                        new ReleasedQuota(
                                rs.getObject("product_id", UUID.class), rs.getInt("released")))
                .list();
    }

    @Override
    public boolean takeOne(UUID itemId, Instant now) {
        // The hot row of a flash sale. Kept as the last statement of the purchase transaction so
        // the row lock is held for as short a time as possible. The version column is left alone:
        // admins cannot edit an item once its slot is open, so there is nothing to protect.
        int updated =
                jdbc.sql(
                                """
                                UPDATE flash_sale_items i
                                   SET sold = i.sold + 1, updated_at = :now
                                  FROM flash_sale_slots s
                                 WHERE i.id = :itemId
                                   AND s.id = i.slot_id
                                   AND s.status = 'PUBLISHED'
                                   AND s.start_at <= :now AND s.end_at > :now
                                   AND i.sold < i.quota
                                """)
                        .param("itemId", itemId)
                        .param("now", Timestamp.from(now))
                        .update();
        return updated == 1;
    }

    @Override
    public Optional<ItemSnapshot> find(UUID itemId) {
        return jdbc.sql(SNAPSHOT + " WHERE i.id = :itemId")
                .param("itemId", itemId)
                .query(JdbcFlashSaleItemStore::snapshot)
                .optional();
    }

    @Override
    public List<ItemSnapshot> findOpen(Instant now) {
        return jdbc.sql(
                        SNAPSHOT
                                + " WHERE s.status = 'PUBLISHED' AND s.start_at <= :now AND"
                                + " s.end_at > :now")
                .param("now", Timestamp.from(now))
                .query(JdbcFlashSaleItemStore::snapshot)
                .list();
    }

    private static ItemSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new ItemSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("slot_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getBigDecimal("sale_price"),
                rs.getInt("remaining"),
                SlotStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant());
    }
}
