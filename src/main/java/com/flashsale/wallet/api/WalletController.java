package com.flashsale.wallet.api;

import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.wallet.application.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallet")
@RestController
class WalletController {

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @Operation(summary = "My balance")
    @GetMapping("/api/v1/wallet")
    BalanceResponse myBalance(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return new BalanceResponse(userId, wallets.balance(userId));
    }

    @Operation(
            summary = "Top up a user's wallet (admin)",
            description = "The assignment assumes users already hold balance; this puts it there.")
    @PostMapping("/api/v1/admin/wallets/{userId}/top-up")
    BalanceResponse topUp(
            @PathVariable UUID userId,
            @Valid @RequestBody TopUpRequest body,
            @Parameter(description = "Optional UUID; retrying with the same key credits only once")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey) {
        return new BalanceResponse(
                userId, wallets.topUp(userId, body.amount(), parseKey(idempotencyKey)));
    }

    private static UUID parseKey(String key) {
        if (key == null) {
            return null;
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID");
        }
    }

    record TopUpRequest(
            @NotNull
                    @DecimalMin(value = "0.01")
                    @DecimalMax(value = "1000000000")
                    @Digits(integer = 10, fraction = 2)
                    BigDecimal amount) {}

    record BalanceResponse(UUID userId, BigDecimal balance) {}
}
