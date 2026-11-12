package com.flashsale.auth.domain;

public enum UserStatus {
    /** Registered, waiting for OTP verification. Cannot log in. */
    PENDING,
    ACTIVE,
    LOCKED
}
