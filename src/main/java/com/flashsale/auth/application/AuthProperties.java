package com.flashsale.auth.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwtSecret HMAC key for signing access tokens; at least 32 bytes (HS256)
 * @param otpHmacKey key used to hash OTP codes before storing them
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String jwtSecret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotBlank @Size(min = 32) String otpHmacKey,
        @NotNull Duration otpTtl,
        @Min(1) int otpMaxAttempts,
        @NotNull Duration otpResendCooldown) {}
