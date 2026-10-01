package com.tazzzo.membership;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-16A-1 — an opaque, CSPRNG-generated Membership term identity ({@code MBR_...}). Mirrors
 * {@code OrderId}/{@code InventoryReservationId}: never sequential, never derived from any input,
 * bounded length, conservative alphabet.
 */
public record MembershipId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^MBR_[A-Za-z0-9_-]{6,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public MembershipId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid membership id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** Generated ONCE per grant, BEFORE entering any transaction, so a transaction retry reuses the
     *  SAME logical identity rather than minting a new one on every attempt. */
    public static MembershipId generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new MembershipId("MBR_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
