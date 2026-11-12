package com.flashsale.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.auth.domain.NotificationMessageRepository;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.ratelimit.RateLimitProperties;
import com.flashsale.shared.ratelimit.RateLimiter;
import com.flashsale.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "Secret123";
    private static final Pattern SIX_DIGITS = Pattern.compile("(\\d{6})");

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired NotificationMessageRepository messages;
    @Autowired StringRedisTemplate redis;

    @Test
    void fullLifecycle_register_verify_login_me_refresh_logout() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isAccepted());

        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        verify(email, latestOtp(email)).andExpect(status().isOk());

        JsonNode tokens = body(login(email, PASSWORD).andExpect(status().isOk()));
        String access = tokens.get("accessToken").asString();
        String refresh = tokens.get("refreshToken").asString();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier").value(email))
                .andExpect(jsonPath("$.role").value("USER"));

        JsonNode rotated =
                body(
                        post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}")
                                .andExpect(status().isOk()));
        String newAccess = rotated.get("accessToken").asString();

        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/v1/auth/logout")
                                .header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void registerAnswersIdenticallyForNewAndExistingAccounts() throws Exception {
        String email = uniqueEmail();
        String first = register(email, PASSWORD).andReturn().getResponse().getContentAsString();
        verify(email, latestOtp(email)).andExpect(status().isOk());
        String again =
                register(email, "Other456")
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(again).isEqualTo(first);
        login(email, PASSWORD).andExpect(status().isOk());
        login(email, "Other456").andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserAndWrongPasswordGiveTheSameError() throws Exception {
        String email = activatedUser();
        String wrongPassword =
                login(email, "Wrong999").andReturn().getResponse().getContentAsString();
        String unknownUser =
                login(uniqueEmail(), PASSWORD).andReturn().getResponse().getContentAsString();
        assertThat(code(wrongPassword)).isEqualTo("INVALID_CREDENTIALS");
        assertThat(code(unknownUser)).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void otpIsDestroyedAfterFiveWrongAttempts() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String correct = latestOtp(email);
        String wrong = correct.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            verify(email, wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        verify(email, correct).andExpect(status().isBadRequest());
    }

    @Test
    void phoneNumberWrittenDifferentlyIsTheSameAccount() throws Exception {
        String local = "09" + (10_000_000 + (int) (Math.random() * 89_999_999));
        String international = "+84" + local.substring(1);
        register(local, PASSWORD).andExpect(status().isAccepted());
        verify(international, latestOtp(international)).andExpect(status().isOk());
        login(local, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeLogin() throws Exception {
        String email = activatedUser();
        String firstRefresh = body(login(email, PASSWORD)).get("refreshToken").asString();
        String secondRefresh =
                body(post("/api/v1/auth/refresh", refreshBody(firstRefresh)))
                        .get("refreshToken")
                        .asString();

        post("/api/v1/auth/refresh", refreshBody(firstRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        post("/api/v1/auth/refresh", refreshBody(secondRefresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputReturnsFieldErrors() throws Exception {
        register("not-an-identifier", PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        register(uniqueEmail(), "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void rateLimiterBlocksAfterTheLimitWithinTheWindow() {
        RateLimiter limiter = new RateLimiter(redis, new RateLimitProperties(true));
        String subject = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            limiter.check("test", subject, 3, Duration.ofMinutes(1));
        }
        assertThatThrownBy(() -> limiter.check("test", subject, 3, Duration.ofMinutes(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many requests");
    }

    @Test
    void resendDeliversANewWorkingCode() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String first = latestOtp(email);
        redis.delete("otp:cooldown:" + email);
        post("/api/v1/auth/resend-otp", "{\"identifier\":\"" + email + "\"}")
                .andExpect(status().isAccepted());
        String second = latestOtp(email);
        assertThat(messages.count()).isPositive();
        verify(email, second).andExpect(status().isOk());
        if (!first.equals(second)) {
            login(email, PASSWORD).andExpect(status().isOk());
        }
    }

    @Test
    void passwordGivenAtVerifyWinsOverTheOneGivenAtRegister() throws Exception {
        String email = uniqueEmail();
        register(email, "Attacker999").andExpect(status().isAccepted());
        verify(email, latestOtp(email), "Victim12345").andExpect(status().isOk());
        login(email, "Victim12345").andExpect(status().isOk());
        login(email, "Attacker999").andExpect(status().isUnauthorized());
    }

    @Test
    void registeringAgainOnAPendingAccountNeverChangesThePassword() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        register(email, "Attacker999").andExpect(status().isAccepted());
        verify(email, latestOtp(email), "Victim12345").andExpect(status().isOk());
        login(email, "Victim12345").andExpect(status().isOk());
        login(email, "Attacker999").andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordLongerThan72BytesIsRejectedWith400() throws Exception {
        String tooLong = "Ab1" + "\u00e9".repeat(40); // 43 characters, 83 UTF-8 bytes
        register(uniqueEmail(), tooLong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.password").exists());
        String email = uniqueEmail();
        register(email, PASSWORD);
        verify(email, latestOtp(email), tooLong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void weakPasswordAtVerifyIsRejectedAndKeepsTheOtpUsable() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String otp = latestOtp(email);
        verify(email, otp, "short").andExpect(status().isBadRequest());
        verify(email, otp, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void concurrentRegistrationsOfTheSameIdentifierAllAnswer202() throws Exception {
        String email = uniqueEmail();
        java.util.List<java.util.concurrent.Callable<Integer>> calls =
                java.util.stream.IntStream.range(0, 8)
                        .<java.util.concurrent.Callable<Integer>>mapToObj(i ->
                                () ->
                                        register(email, PASSWORD)
                                                .andReturn()
                                                .getResponse()
                                                .getStatus())
                        .toList();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : pool.invokeAll(calls)) {
                assertThat(result.get()).isEqualTo(202);
            }
        }
    }

    @Test
    void activationOpensAnEmptyWallet() throws Exception {
        String email = activatedUser();
        String access = body(login(email, PASSWORD)).get("accessToken").asString();
        mvc.perform(get("/api/v1/wallet").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.0));
    }

    @Test
    void logoutCannotRevokeSomeoneElsesRefreshToken() throws Exception {
        String victim = activatedUser();
        String victimRefresh = body(login(victim, PASSWORD)).get("refreshToken").asString();
        String attacker = activatedUser();
        String attackerAccess = body(login(attacker, PASSWORD)).get("accessToken").asString();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/v1/auth/logout")
                                .header("Authorization", "Bearer " + attackerAccess)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(refreshBody(victimRefresh)))
                .andExpect(status().isNoContent());
        post("/api/v1/auth/refresh", refreshBody(victimRefresh)).andExpect(status().isOk());
    }

    private String activatedUser() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        verify(email, latestOtp(email)).andExpect(status().isOk());
        return email;
    }

    private String latestOtp(String identifier) {
        String normalized =
                com.flashsale.auth.domain.Identifier.parse(identifier).orElseThrow().value();
        String body =
                messages.findFirstByRecipientOrderByCreatedAtDesc(normalized).orElseThrow().body();
        Matcher matcher = SIX_DIGITS.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private ResultActions register(String identifier, String password) throws Exception {
        return post(
                "/api/v1/auth/register",
                "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions verify(String identifier, String otp) throws Exception {
        return verify(identifier, otp, PASSWORD);
    }

    private ResultActions verify(String identifier, String otp, String password) throws Exception {
        return post(
                "/api/v1/auth/verify-otp",
                "{\"identifier\":\"%s\",\"otp\":\"%s\",\"password\":\"%s\"}"
                        .formatted(identifier, otp, password));
    }

    private ResultActions login(String identifier, String password) throws Exception {
        return post(
                "/api/v1/auth/login",
                "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions post(String path, String jsonBody) throws Exception {
        return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody));
    }

    private static String refreshBody(String token) {
        return "{\"refreshToken\":\"" + token + "\"}";
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String code(String responseBody) {
        return json.readTree(responseBody).get("code").asString();
    }

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 12) + "@test.local";
    }
}
