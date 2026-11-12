package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.support.BlockedShoppersRule;
import com.flashsale.support.FlashSaleFixtures;
import com.flashsale.support.FlashSaleFixtures.OpenSlot;
import com.flashsale.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A new flash sale condition is just a {@code PurchaseRule} bean; the purchase flow is unchanged.
 */
@IntegrationTest
class PurchaseRuleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FlashSaleFixtures fixtures;
    @Autowired BlockedShoppersRule blockedShoppers;

    private OpenSlot slot;
    private UUID user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            blockedShoppers.unblock(user);
        }
        if (slot != null) {
            fixtures.close(slot);
        }
    }

    @Test
    void aRuleRejectsTheShopperBeforeAnyStockIsTaken() throws Exception {
        slot = fixtures.openSlotWithItems(new BigDecimal("100000.00"), 1);
        user = fixtures.usersWithBalance(1, new BigDecimal("1000000.00")).getFirst();
        blockedShoppers.block(user);

        mvc.perform(buy(user, slot.item(0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_ALLOWED"));
        assertThat(fixtures.sold(slot.item(0))).isZero();

        // The only unit is still available: once the rule lets the shopper through, they get it.
        blockedShoppers.unblock(user);
        mvc.perform(buy(user, slot.item(0))).andExpect(status().isCreated());
        assertThat(fixtures.sold(slot.item(0))).isEqualTo(1);
    }

    private static MockHttpServletRequestBuilder buy(UUID user, UUID itemId) {
        return post("/api/v1/flash-sale/items/" + itemId + "/purchase")
                .with(
                        jwt().jwt(j -> j.subject(user.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }
}
