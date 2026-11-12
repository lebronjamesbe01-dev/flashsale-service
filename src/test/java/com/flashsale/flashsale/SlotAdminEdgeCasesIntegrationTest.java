package com.flashsale.flashsale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class SlotAdminEdgeCasesIntegrationTest {

    private static final RequestPostProcessor ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void concurrentDuplicateItemsAreRejectedWithTheSpecificCode() throws Exception {
        UUID product = product(100);
        UUID slot = slot("DRAFT", Instant.now().plus(Duration.ofDays(4000)));
        String body = "{\"productId\":\"" + product + "\",\"salePrice\":50,\"quota\":10}";

        List<Integer> statuses = parallel(6, () -> httpStatus(send(addItem(slot), body)));
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
        send(addItem(slot), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_ALREADY_IN_SLOT"));
        assertThat(reserved(product)).isEqualTo(10);
    }

    @Test
    void concurrentDuplicateSkuIsRejectedWithTheSpecificCode() throws Exception {
        String sku = "DUP-" + UUID.randomUUID().toString().substring(0, 8);
        String body =
                "{\"sku\":\"" + sku + "\",\"name\":\"n\",\"listPrice\":1000,\"initialStock\":5}";
        List<Integer> statuses =
                parallel(6, () -> httpStatus(send(post("/api/v1/admin/products"), body)));
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
        send(post("/api/v1/admin/products"), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void itemEditsFreezeShortlyBeforeTheSlotStarts() throws Exception {
        UUID product = product(100);
        UUID slot = slot("PUBLISHED", Instant.now().plusSeconds(60));
        UUID item = item(slot, product, 10);
        String url = "/api/v1/admin/flash-sale/slots/" + slot + "/items/" + item;
        try {
            send(put(url), "{\"salePrice\":40,\"quota\":12}").andExpect(status().isOk());
            assertThat(reserved(product)).isEqualTo(12);

            // 5 s to go: inside the freeze margin
            jdbc.sql("update flash_sale_slots set start_at = ?, end_at = ? where id = ?")
                    .params(
                            Timestamp.from(Instant.now().plusSeconds(5)),
                            Timestamp.from(Instant.now().plusSeconds(600)),
                            slot)
                    .update();
            send(put(url), "{\"salePrice\":40,\"quota\":20}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SLOT_NOT_EDITABLE"));
            send(addItem(slot), "{\"productId\":\"" + product + "\",\"salePrice\":40,\"quota\":1}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SLOT_NOT_EDITABLE"));
            assertThat(reserved(product)).isEqualTo(12);
        } finally {
            jdbc.sql("update flash_sale_slots set status = 'CLOSED' where id = ?")
                    .param(slot)
                    .update();
        }
    }

    @Test
    void inventoryAdjustIsBoundedAndZeroIsANoOp() throws Exception {
        UUID product = product(100);
        String url = "/api/v1/admin/inventory/" + product + "/adjust";
        jdbc.sql("update inventory set on_hand = 1000000000 where product_id = ?")
                .param(product)
                .update();

        send(post(url), "{\"delta\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INVENTORY_ADJUSTMENT"));
        send(post(url), "{\"delta\":10000000}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INVENTORY_ADJUSTMENT"));

        long versionBefore = version(product);
        send(post(url), "{\"delta\":0}").andExpect(status().isOk());
        assertThat(version(product)).isEqualTo(versionBefore);

        send(post(url), "{\"delta\":-1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(999_999_999));
    }

    private static MockHttpServletRequestBuilder addItem(UUID slot) {
        return post("/api/v1/admin/flash-sale/slots/" + slot + "/items");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String payload)
            throws Exception {
        return mvc.perform(
                request.with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private static int httpStatus(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private static List<Integer> parallel(int n, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID slot(String status, Instant start) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into flash_sale_slots (id, name, start_at, end_at, status,"
                                + " created_at, updated_at) values (?, 'edge', ?, ?, ?, ?, ?)")
                .params(
                        id,
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(600)),
                        status,
                        ts,
                        ts)
                .update();
        return id;
    }

    private UUID product(int onHand) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into products (id, sku, name, list_price, status, created_at,"
                                + " updated_at) values (?, ?, 'edge', 100, 'ACTIVE', ?, ?)")
                .params(id, "E-" + id, ts, ts)
                .update();
        jdbc.sql(
                        "insert into inventory (product_id, on_hand, reserved, updated_at)"
                                + " values (?, ?, 0, ?)")
                .params(id, onHand, ts)
                .update();
        return id;
    }

    /** Inserts an item and reserves its quota, as the admin API would. */
    private UUID item(UUID slotId, UUID productId, int quota) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(Instant.now());
        jdbc.sql(
                        "insert into flash_sale_items (id, slot_id, product_id, sale_price, quota,"
                                + " sold, created_at, updated_at) values (?, ?, ?, 50, ?, 0, ?, ?)")
                .params(id, slotId, productId, quota, ts, ts)
                .update();
        jdbc.sql("update inventory set reserved = ? where product_id = ?")
                .params(quota, productId)
                .update();
        return id;
    }

    private int reserved(UUID productId) {
        return jdbc.sql("select reserved from inventory where product_id = ?")
                .param(productId)
                .query(Integer.class)
                .single();
    }

    private long version(UUID productId) {
        return jdbc.sql("select version from inventory where product_id = ?")
                .param(productId)
                .query(Long.class)
                .single();
    }
}
