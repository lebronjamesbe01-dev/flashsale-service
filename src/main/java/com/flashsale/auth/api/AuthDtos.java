package com.flashsale.auth.api;

import com.flashsale.auth.application.TokenPair;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the auth API. */
final class AuthDtos {

    private AuthDtos() {}

    record RegisterRequest(
            @Schema(description = "Email or phone number", example = "an@example.com")
                    @NotBlank
                    @Size(max = 320)
                    String identifier,
            @Schema(example = "Secret123")
                    @NotBlank
                    @Size(min = 8, max = 72, message = "must be 8 to 72 characters")
                    @Pattern(
                            regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                            message = "must contain at least one letter and one digit")
                    String password) {}

    record VerifyOtpRequest(
            @NotBlank @Size(max = 320) String identifier,
            @Schema(example = "123456")
                    @NotBlank
                    @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits")
                    String otp) {}

    record IdentifierRequest(@NotBlank @Size(max = 320) String identifier) {}

    record LoginRequest(
            @NotBlank @Size(max = 320) String identifier,
            @NotBlank @Size(max = 72) String password) {}

    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    record LogoutRequest(@Size(max = 200) String refreshToken) {}

    record MessageResponse(String message) {}

    record TokenResponse(
            String accessToken, String refreshToken, String tokenType, long expiresIn) {

        static TokenResponse of(TokenPair pair) {
            return new TokenResponse(
                    pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds());
        }
    }
}
