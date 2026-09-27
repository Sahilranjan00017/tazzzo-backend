package com.tazzzo.auth.otp;

import java.security.SecureRandom;

/** PR-11B — a 6-digit decimal OTP, generated with {@link SecureRandom}, never {@code Math.random()}. */
final class OtpCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OtpCodeGenerator() {
    }

    static String generate() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }
}
