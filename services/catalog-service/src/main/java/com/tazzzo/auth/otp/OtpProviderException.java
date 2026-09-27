package com.tazzzo.auth.otp;

/**
 * PR-11B — the formal exception contract for {@link OtpDeliveryProvider} adapters. A production
 * vendor adapter (Twilio/MSG91/SNS/WhatsApp — none shipped in this PR) should throw this, wrapping
 * the vendor-specific failure, so {@code OtpService} can log a bounded, safe class-name-only event.
 * {@code OtpService} still catches the broader {@code RuntimeException} defensively — a
 * misbehaving adapter that throws something else must not escape as an uncaught 500 — but this is
 * the type an adapter SHOULD throw, and vendor exception messages/details must never be attached to
 * the message of a broader wrapper that could leak through logging.
 */
public class OtpProviderException extends RuntimeException {

    public OtpProviderException(String message) {
        super(message);
    }

    public OtpProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
