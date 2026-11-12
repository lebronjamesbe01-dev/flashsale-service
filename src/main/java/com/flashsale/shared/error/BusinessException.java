package com.flashsale.shared.error;

/**
 * The only exception type business code throws on purpose. It is translated into an RFC 9457
 * problem response by {@link GlobalExceptionHandler}.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
