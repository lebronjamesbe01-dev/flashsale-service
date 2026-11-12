package com.flashsale.flashsale;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class FlashSaleConfigIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final RequestPostProcessor ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    private static final RequestPostProcessor USER =
            jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_USER"));

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void quotasAreBackedByWarehouseStockAndReturnedOnClose() throws Exception {
        String productId = createProduct(100);
        ZonedDateTime day = futureDay();
        String slotA = createSlot("A", day.withHour(10), day.withHour(11));
        String slotB = createSlot("B", day.withHour(12), day.withHour(13));

        String itemId =
                body(addItem(slotA, productId, 60).andExpect(status().isCreated()))
                        .get("items")
                        .get(0)
                        .get("itemId")
                        .asString();
        expectInventory(productId, 100, 60, 40);

        addItem(slotB, productId, 50)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));

        send(
                        put("/api/v1/admin/flash-sale/slots/" + slotA + "/items/" + itemId),
                        "{\"salePrice\":900000,\"quota\":30}")
                .andExpect(status().isOk());
        expectInventory(productId, 100, 30, 70);

        send(post("/api/v1/admin/inventory/" + productId + "/adjust"), "{\"delta\":-80}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INVENTORY_ADJUSTMENT"));

        send(post("/api/v1/admin/flash-sale/slots/" + slotA + "/close"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        expectInventory(productId, 100, 0, 100);
    }

    @Test
    void publishedSlotsCannotOverlap() throws Exception {
        ZonedDateTime day = futureDay();
        String first = createSlot("first", day.withHour(14), day.withHour(16));
        String second = createSlot("second", day.withHour(15), day.withHour(17));
        publish(first).andExpect(status().isOk());
        publish(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
    }

    @Test
    void closingASlotFreesItsWindowAndDraftsCanBeCancelled() throws Exception {
        ZonedDateTime day = futureDay();
        String first = createSlot("first", day.withHour(8), day.withHour(9));
        publish(first).andExpect(status().isOk());
        String overlappingDraft = createSlot("draft", day.withHour(8), day.withHour(9));
        send(post("/api/v1/admin/flash-sale/slots/" + overlappingDraft + "/close"), "")
                .andExpect(status().isOk());
        send(post("/api/v1/admin/flash-sale/slots/" + first + "/close"), "")
                .andExpect(status().isOk());
        String replacement = createSlot("replacement", day.withHour(8), day.withHour(9));
        publish(replacement).andExpect(status().isOk());
    }

    @Test
    void topUpRejectsUnknownUsers() throws Exception {
        send(post("/api/v1/admin/wallets/" + UUID.randomUUID() + "/top-up"), "{\"amount\":1000}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void slotsCannotCrossMidnight() throws Exception {
        ZonedDateTime day = futureDay();
        send(
                        post("/api/v1/admin/flash-sale/slots"),
                        slotJson("late", day.withHour(23), day.plusDays(1).withHour(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SLOT_TIME"));
    }

    @Test
    void currentReturnsTheOpenSlotWithRemainingQuantity() throws Exception {
        String productId = createProduct(20);
        ZonedDateTime now = ZonedDateTime.now(VN);
        ZonedDateTime start = now.minusMinutes(1);
        if (!start.toLocalDate().equals(now.toLocalDate())) {
            start = now.toLocalDate().atStartOfDay(VN);
        }
        ZonedDateTime end = now.plusMinutes(30);
        if (!end.toLocalDate().equals(now.toLocalDate())) {
            end = now.toLocalDate().plusDays(1).atStartOfDay(VN);
        }
        String slot = createSlot("now", start, end);
        addItem(slot, productId, 7).andExpect(status().isCreated());
        publish(slot).andExpect(status().isOk());

        mvc.perform(get("/api/v1/flash-sale/current").with(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot.id").value(slot))
                .andExpect(jsonPath("$.slot.items[0].remaining").value(7));

        mvc.perform(get("/api/v1/flash-sale/slots").with(USER)).andExpect(status().isOk());
        send(post("/api/v1/admin/flash-sale/slots/" + slot + "/close"), "")
                .andExpect(status().isOk());
    }

    @Test
    void usersCannotUseAdminEndpoints() throws Exception {
        mvc.perform(get("/api/v1/admin/flash-sale/slots").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static ZonedDateTime futureDay() {
        LocalDate date = LocalDate.now(VN).plusDays(ThreadLocalRandom.current().nextInt(30, 5000));
        return date.atStartOfDay(VN);
    }

    private String createProduct(int stock) throws Exception {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        String payload =
                "{\"sku\":\""
                        + sku
                        + "\",\"name\":\"Test "
                        + sku
                        + "\",\"listPrice\":1000000,\"initialStock\":"
                        + stock
                        + "}";
        return body(send(post("/api/v1/admin/products"), payload).andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    private String createSlot(String name, ZonedDateTime start, ZonedDateTime end)
            throws Exception {
        return body(send(post("/api/v1/admin/flash-sale/slots"), slotJson(name, start, end))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    private static String slotJson(String name, ZonedDateTime start, ZonedDateTime end) {
        Instant s = start.toInstant();
        Instant e = end.toInstant();
        return "{\"name\":\"" + name + "\",\"startAt\":\"" + s + "\",\"endAt\":\"" + e + "\"}";
    }

    private ResultActions addItem(String slotId, String productId, int quota) throws Exception {
        return send(
                post("/api/v1/admin/flash-sale/slots/" + slotId + "/items"),
                "{\"productId\":\""
                        + productId
                        + "\",\"salePrice\":500000,\"quota\":"
                        + quota
                        + "}");
    }

    private ResultActions publish(String slotId) throws Exception {
        return send(post("/api/v1/admin/flash-sale/slots/" + slotId + "/publish"), "");
    }

    private void expectInventory(String productId, int onHand, int reserved, int available)
            throws Exception {
        mvc.perform(get("/api/v1/admin/inventory/" + productId).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(onHand))
                .andExpect(jsonPath("$.reserved").value(reserved))
                .andExpect(jsonPath("$.available").value(available));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String payload)
            throws Exception {
        MockHttpServletRequestBuilder withAdmin = request.with(ADMIN);
        if (!payload.isEmpty()) {
            withAdmin = withAdmin.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        return mvc.perform(withAdmin);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
