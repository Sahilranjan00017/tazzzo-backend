package com.tazzzo.auth.otp;

/**
 * PR-11B — proof of successful OTP verification. {@code grantId} is the ONLY thing the client
 * receives; it never carries the phone number. PR-11C atomically consumes this grant to create a
 * customer session — this PR does not.
 */
public record OtpVerifyResult(String challengeId, String grantId) {
}
