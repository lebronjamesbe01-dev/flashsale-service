package com.flashsale.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.support.FlashSaleFixtures;
import com.flashsale.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** An admin client that retries a top-up after a timeout must not credit the user twice. */
@IntegrationTest
class WalletTopUpIdempotencyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FlashSaleFixtures fixtures;
    @Autowired JdbcClient jdbc;

    @Test
    void theSameKeyCreditsOnlyOnce() throws Exception {
        UUID user = fixtures.usersWithBalance(1, BigDecimal.ZERO).getFirst();
        String key = UUID.randomUUID().toString();

        mvc.perform(topUp(user, "50000.00", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50000.00));
        mvc.perform(topUp(user, "50000.00", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50000.00));

        assertThat(balanceOf(user)).isEqualByComparingTo("50000.00");
        assertThat(ledgerLines(user)).isEqualTo(1);
    }

    @Test
    void differentKeysAndNoKeyEachCredit() throws Exception {
        UUID user = fixtures.usersWithBalance(1, BigDecimal.ZERO).getFirst();

        mvc.perform(topUp(user, "100.00", UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(topUp(user, "100.00", UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(topUp(user, "100.00", null)).andExpect(status().isOk());

        assertThat(balanceOf(user)).isEqualByComparingTo("300.00");
        assertThat(ledgerLines(user)).isEqualTo(3);
    }

    @Test
    void ledgerBalanceAfterMatchesTheWallet() throws Exception {
        UUID user = fixtures.usersWithBalance(1, new BigDecimal("10.00")).getFirst();

        mvc.perform(topUp(user, "5.50", UUID.randomUUID().toString())).andExpect(status().isOk());

        BigDecimal balanceAfter =
                jdbc.sql("select balance_after from wallet_transactions where user_id = ?")
                        .param(user)
                        .query(BigDecimal.class)
                        .single();
        assertThat(balanceAfter).isEqualByComparingTo("15.50");
    }

    @Test
    void aKeyReusedForAnotherUserOrAmountIsRejected() throws Exception {
        List<UUID> users = fixtures.usersWithBalance(2, BigDecimal.ZERO);
        String key = UUID.randomUUID().toString();
        mvc.perform(topUp(users.get(0), "100.00", key)).andExpect(status().isOk());

        mvc.perform(topUp(users.get(1), "100.00", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(topUp(users.get(0), "200.00", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balanceOf(users.get(0))).isEqualByComparingTo("100.00");
        assertThat(balanceOf(users.get(1))).isEqualByComparingTo("0");
    }

    @Test
    void aKeyThatIsNotAUuidIsRejected() throws Exception {
        UUID user = fixtures.usersWithBalance(1, BigDecimal.ZERO).getFirst();

        mvc.perform(topUp(user, "10.00", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(balanceOf(user)).isEqualByComparingTo("0");
    }

    private MockHttpServletRequestBuilder topUp(UUID user, String amount, String key) {
        var request =
                post("/api/v1/admin/wallets/" + user + "/top-up")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}");
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private BigDecimal balanceOf(UUID user) {
        return jdbc.sql("select balance from wallets where user_id = ?")
                .param(user)
                .query(BigDecimal.class)
                .single();
    }

    private int ledgerLines(UUID user) {
        return jdbc.sql("select count(*) from wallet_transactions where user_id = ?")
                .param(user)
                .query(Integer.class)
                .single();
    }
}
