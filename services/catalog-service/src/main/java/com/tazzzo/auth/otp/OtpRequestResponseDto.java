package com.tazzzo.auth.otp;

/** PR-11B — never carries the OTP, the phone, or any hint of customer existence. */
public record OtpRequestResponseDto(String challengeId, long expiresInSeconds, long resendAfterSeconds,
                                    String requestId) {
}
