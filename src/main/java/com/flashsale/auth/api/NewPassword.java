package com.flashsale.auth.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rules for a password being set (register, verify-otp): 8 characters minimum, a letter and a
 * digit, and at most {@link #MAX_BYTES} UTF-8 bytes (a cap so huge inputs cannot be sent to the
 * hasher).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@NotBlank
@Size(min = 8, message = "must be at least 8 characters")
@Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
        message = "must contain at least one letter and one digit")
@Constraint(validatedBy = Utf8ByteLimitValidator.class)
@interface NewPassword {

    int MAX_BYTES = 128;

    String message() default "must be at most 128 bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
