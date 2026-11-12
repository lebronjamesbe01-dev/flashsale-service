package com.flashsale.auth.domain;

/** Delivers an OTP by email or SMS. The assignment allows a mock (log / database outbox). */
public interface OtpSender {

    void send(Identifier recipient, String code);
}
