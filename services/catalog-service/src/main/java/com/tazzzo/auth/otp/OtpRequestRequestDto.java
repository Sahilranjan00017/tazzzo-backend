package com.tazzzo.auth.otp;

/** PR-11B — {@code POST /v1/auth/otp/request} body. {@code phone} is bounded before parsing. */
public record OtpRequestRequestDto(String phone) {
}
