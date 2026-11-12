package com.flashsale.flashsale.infrastructure;

import com.flashsale.flashsale.domain.FlashSaleItemStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcFlashSaleItemStore implements FlashSaleItemStore {

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
                .param("now", java.sql.Timestamp.from(now))
                .query((rs, i) ->
                        new ReleasedQuota(
                                rs.getObject("product_id", UUID.class), rs.getInt("released")))
                .list();
    }
}
