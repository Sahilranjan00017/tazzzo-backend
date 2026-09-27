package com.tazzzo.auth.otp;

/** PR-11B — never carries the OTP or the phone; safe to serialize directly to the client. */
public record OtpRequestResult(String challengeId, long expiresInSeconds, long resendAfterSeconds) {
}
