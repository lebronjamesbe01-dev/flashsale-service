package com.flashsale.auth.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/** Rejects passwords BCrypt cannot take: more than {@link NewPassword#MAX_BYTES} UTF-8 bytes. */
final class Utf8ByteLimitValidator implements ConstraintValidator<NewPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null
                || value.getBytes(StandardCharsets.UTF_8).length <= NewPassword.MAX_BYTES;
    }
}
