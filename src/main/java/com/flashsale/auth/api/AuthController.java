package com.flashsale.auth.api;

import com.flashsale.auth.api.AuthDtos.IdentifierRequest;
import com.flashsale.auth.api.AuthDtos.LoginRequest;
import com.flashsale.auth.api.AuthDtos.LogoutRequest;
import com.flashsale.auth.api.AuthDtos.MessageResponse;
import com.flashsale.auth.api.AuthDtos.RefreshRequest;
import com.flashsale.auth.api.AuthDtos.RegisterRequest;
import com.flashsale.auth.api.AuthDtos.TokenResponse;
import com.flashsale.auth.api.AuthDtos.VerifyOtpRequest;
import com.flashsale.auth.application.AuthenticationService;
import com.flashsale.auth.application.RegistrationService;
import com.flashsale.auth.application.UserProfile;
import com.flashsale.auth.domain.Identifier;
import com.flashsale.shared.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Register, verify OTP, login, refresh, logout")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private static final String OTP_SENT =
            "If the information is valid, a verification code has been sent.";
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final RateLimiter rateLimiter;

    AuthController(
            RegistrationService registration,
            AuthenticationService authentication,
            RateLimiter rateLimiter) {
        this.registration = registration;
        this.authentication = authentication;
        this.rateLimiter = rateLimiter;
    }

    @Operation(
            summary = "Register with email or phone",
            description =
                    "Creates a pending account and sends a 6-digit OTP. Always answers 202 with the"
                            + " same message, whether or not the identifier is already registered.")
    @SecurityRequirements
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    MessageResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest http) {
        rateLimiter.check("register-ip", clientIp(http), 10, MINUTE);
        Identifier identifier = IdentifierParser.parse(body.identifier());
        registration.register(identifier, body.password());
        return new MessageResponse(OTP_SENT);
    }

    @Operation(
            summary = "Verify OTP and activate the account",
            description =
                    "Proves ownership of the identifier and sets the account password. Five wrong"
                            + " codes destroy the OTP; request a new one with resend-otp.")
    @SecurityRequirements
    @PostMapping("/verify-otp")
    MessageResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest body, HttpServletRequest http) {
        rateLimiter.check("verify-ip", clientIp(http), 30, MINUTE);
        Identifier identifier = IdentifierParser.parse(body.identifier());
        // Caps guesses across resends too: 5 per code, at most 10 per hour per identifier.
        rateLimiter.check("verify-id", identifier.value(), 10, HOUR);
        registration.verifyOtp(identifier, body.otp(), body.password());
        return new MessageResponse("Account verified. You can now log in.");
    }

    @Operation(
            summary = "Send a new OTP",
            description = "At most one code per 60 seconds per identifier. Always answers 202.")
    @SecurityRequirements
    @PostMapping("/resend-otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    MessageResponse resendOtp(@Valid @RequestBody IdentifierRequest body, HttpServletRequest http) {
        rateLimiter.check("resend-ip", clientIp(http), 10, MINUTE);
        Identifier identifier = IdentifierParser.parse(body.identifier());
        rateLimiter.check("resend-id", identifier.value(), 5, HOUR);
        registration.resendOtp(identifier);
        return new MessageResponse(OTP_SENT);
    }

    @Operation(
            summary = "Log in with email or phone",
            description =
                    "Returns a 15-minute access token and a refresh token. Any failure returns the"
                            + " same INVALID_CREDENTIALS error.")
    @SecurityRequirements
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest http) {
        rateLimiter.check("login-ip", clientIp(http), 20, MINUTE);
        Identifier identifier = IdentifierParser.parse(body.identifier());
        rateLimiter.check("login-id", identifier.value(), 10, MINUTE);
        return TokenResponse.of(authentication.login(identifier, body.password()));
    }

    @Operation(
            summary = "Exchange a refresh token for a new token pair",
            description =
                    "Refresh tokens are single-use. Reusing an old one revokes the whole login.")
    @SecurityRequirements
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest http) {
        rateLimiter.check("refresh-ip", clientIp(http), 30, MINUTE);
        return TokenResponse.of(authentication.refresh(body.refreshToken()));
    }

    @Operation(
            summary = "Log out",
            description =
                    "Revokes the current access token immediately and, if given, the refresh token.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) LogoutRequest body) {
        authentication.logout(
                UUID.fromString(jwt.getSubject()),
                jwt.getId(),
                jwt.getExpiresAt(),
                body == null ? null : body.refreshToken());
    }

    /** Behind nginx the container is configured to resolve the real client address here. */
    private static String clientIp(HttpServletRequest http) {
        return http.getRemoteAddr();
    }

    @Operation(summary = "Current user")
    @GetMapping("/me")
    UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return authentication.profile(UUID.fromString(jwt.getSubject()));
    }
}
