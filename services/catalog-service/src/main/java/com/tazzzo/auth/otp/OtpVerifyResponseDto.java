package com.tazzzo.auth.otp;

/**
 * PR-11B — proof of successful OTP verification only. {@code grantId} is opaque and carries no
 * phone/PII; it is NOT a customer access or refresh token — no session or customer exists yet.
 */
public record OtpVerifyResponseDto(String challengeId, boolean verified, String grantId, String requestId) {
}
