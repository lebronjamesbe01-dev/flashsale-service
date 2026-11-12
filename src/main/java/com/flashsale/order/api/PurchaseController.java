package com.flashsale.order.api;

import com.flashsale.order.application.OrderView;
import com.flashsale.order.application.PurchaseService;
import com.flashsale.order.application.PurchaseService.PurchaseResult;
import com.flashsale.shared.error.BusinessException;
import com.flashsale.shared.error.ErrorCode;
import com.flashsale.shared.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flash sale")
@RestController
class PurchaseController {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final PurchaseService purchases;
    private final RateLimiter rateLimiter;

    PurchaseController(PurchaseService purchases, RateLimiter rateLimiter) {
        this.purchases = purchases;
        this.rateLimiter = rateLimiter;
    }

    @Operation(
            summary = "Buy a flash sale item",
            description =
                    "Succeeds only while the item's slot is open, stock remains, the user has not"
                            + " bought a flash sale product today and the wallet covers the price."
                            + " Send a unique Idempotency-Key per purchase attempt; retrying with the"
                            + " same key returns the same order (header Idempotent-Replayed: true).")
    @PostMapping("/api/v1/flash-sale/items/{itemId}/purchase")
    ResponseEntity<PurchaseResponse> purchase(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID itemId,
            @Parameter(description = "Unique per attempt, e.g. a UUID", required = true)
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        rateLimiter.check("purchase-user", userId.toString(), 30, Duration.ofMinutes(1));
        PurchaseResult result = purchases.purchase(userId, itemId, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(new PurchaseResponse(result.order(), result.balanceAfter()));
    }

    @Operation(summary = "My flash sale orders (latest 50)")
    @GetMapping("/api/v1/orders")
    List<OrderView> myOrders(@AuthenticationPrincipal Jwt jwt) {
        return purchases.myOrders(UUID.fromString(jwt.getSubject()));
    }

    /** {@code balanceAfter} is null on a replayed response. */
    record PurchaseResponse(OrderView order, BigDecimal balanceAfter) {}
}
