package com.tazzzo.auth.otp;

/** PR-11B — {@code POST /v1/auth/otp/verify} body. */
public record OtpVerifyRequestDto(String challengeId, String otp) {
}
