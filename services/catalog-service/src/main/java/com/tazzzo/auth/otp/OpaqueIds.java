package com.tazzzo.auth.otp;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-11B — shared opaque-id generation for {@code OTP_*} challenge ids and {@code GRANT_*} verified
 * login grant ids. Cryptographically random ({@link SecureRandom}, never {@code Math.random()}),
 * non-sequential, bounded length, a conservative URL-safe alphabet, and carrying no phone,
 * timestamp, or other PII.
 */
final class OpaqueIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 20 random bytes -> 27 base64url characters; astronomically non-guessable, still bounded. */
    private static final int RANDOM_BYTES = 20;

    static final Pattern CHALLENGE_ID = Pattern.compile("^OTP_[A-Za-z0-9_-]{20,40}$");
    static final Pattern GRANT_ID = Pattern.compile("^GRANT_[A-Za-z0-9_-]{20,40}$");

    private OpaqueIds() {
    }

    static String newChallengeId() {
        return "OTP_" + randomToken();
    }

    static String newGrantId() {
        return "GRANT_" + randomToken();
    }

    private static String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
