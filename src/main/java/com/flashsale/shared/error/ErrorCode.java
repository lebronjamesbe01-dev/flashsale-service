package com.flashsale.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in every error response ({@code code} field).
 * Clients branch on the code, never on the human-readable message. Each feature module adds its own
 * codes here as it is implemented.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request data is invalid"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body cannot be read"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Requested response format is not supported"),
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request content type is not supported"),
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with the current state"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please slow down"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Identifier or password is incorrect"),
    OTP_INVALID(HttpStatus.BAD_REQUEST, "The verification code is invalid or has expired"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or has expired"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    SKU_ALREADY_EXISTS(HttpStatus.CONFLICT, "A product with this SKU already exists"),
    INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT, "Not enough available stock for this quantity"),
    INVALID_INVENTORY_ADJUSTMENT(
            HttpStatus.CONFLICT, "Stock cannot go below the quantity reserved for flash sales"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "No active user with this id"),
    SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "Flash sale slot not found"),
    SLOT_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Flash sale item not found"),
    INVALID_SLOT_TIME(
            HttpStatus.BAD_REQUEST,
            "Slot must end after it starts and stay within one day (Asia/Ho_Chi_Minh)"),
    SLOT_OVERLAP(HttpStatus.CONFLICT, "Slot overlaps another published slot"),
    SLOT_NOT_EDITABLE(
            HttpStatus.CONFLICT, "Slot can no longer be changed (already started or closed)"),
    PRODUCT_ALREADY_IN_SLOT(HttpStatus.CONFLICT, "Product is already part of this slot"),
    PRODUCT_INACTIVE(HttpStatus.CONFLICT, "Product is not active"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    /**
     * Code for a framework-level error that only carries an HTTP status (unknown path, bad
     * parameter type, wrong content type...). Unlisted 4xx fall back to VALIDATION_FAILED.
     */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            default -> status >= 400 && status < 500 ? VALIDATION_FAILED : INTERNAL_ERROR;
        };
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
