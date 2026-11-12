package com.flashsale.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.outbox.application.Events;
import com.flashsale.support.FlashSaleFixtures;
import com.flashsale.support.FlashSaleFixtures.OpenSlot;
import com.flashsale.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/** Section 2.3 of the assignment: stock follows purchases and product changes, exactly once. */
@IntegrationTest
class InventorySyncIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100000.00");

    @Autowired MockMvc mvc;
    @Autowired FlashSaleFixtures fixtures;
    @Autowired RabbitTemplate rabbit;
    @Autowired StringRedisTemplate redis;

    private OpenSlot slot;

    @AfterEach
    void closeSlot() {
        if (slot != null) {
            fixtures.close(slot);
        }
    }

    @Test
    void aPurchaseMovesTheUnitOutOfTheWarehouse() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 5);
        UUID item = slot.item(0);
        UUID product = fixtures.productOf(item);
        UUID user = fixtures.usersWithBalance(1, new BigDecimal("1000000")).getFirst();

        mvc.perform(
                        post("/api/v1/flash-sale/items/" + item + "/purchase")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .with(jwt().jwt(j -> j.subject(user.toString()))))
                .andExpect(status().isCreated());

        // fixture stock: on_hand = 15, reserved = 5
        awaitTrue(
                () -> fixtures.inventory(product)[0] == 14 && fixtures.inventory(product)[1] == 4);
        Long flagTtl =
                redis.getExpire("fs:bought:" + LocalDate.now(FlashSaleFixtures.VN) + ":" + user);
        assertThat(flagTtl).as("daily flag kept until midnight after commit").isGreaterThan(3000);
    }

    @Test
    void aRedeliveredEventIsAppliedOnlyOnce() throws Exception {
        slot = fixtures.openSlotWithItems(PRICE, 5);
        UUID product = fixtures.productOf(slot.item(0));
        String eventId = UUID.randomUUID().toString();
        String body =
                "{\"orderId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\"}";

        for (int i = 0; i < 3; i++) {
            rabbit.send(
                    Events.EXCHANGE,
                    Events.ORDER_PLACED,
                    MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                            .setMessageId(eventId)
                            .setContentType("application/json")
                            .build());
        }

        awaitTrue(() -> fixtures.inventory(product)[0] == 14);
        Thread.sleep(1000);
        assertThat(fixtures.inventory(product)).containsExactly(14, 4);
    }

    @Test
    void deactivatingAProductPullsItFromUpcomingSlotsAndFreesItsStock() throws Exception {
        UUID product = fixtures.upcomingItem(20);
        assertThat(fixtures.inventory(product)).containsExactly(30, 20);

        mvc.perform(
                        put("/api/v1/admin/products/" + product)
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"gone\",\"listPrice\":100,\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        awaitTrue(() -> fixtures.itemsOfProduct(product) == 0);
        assertThat(fixtures.inventory(product)).containsExactly(30, 0);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("condition reached within 15s").isTrue();
    }
}
