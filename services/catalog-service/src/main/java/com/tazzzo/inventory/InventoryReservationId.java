package com.tazzzo.inventory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-14A — an opaque, CSPRNG-generated inventory reservation identity ({@code RESV_...}). Mirrors
 * {@code CheckoutQuoteId}/{@code AddressId}: never sequential, never derived from any input,
 * bounded length, conservative alphabet.
 */
public record InventoryReservationId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^RESV_[A-Za-z0-9_-]{6,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public InventoryReservationId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid inventory reservation id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** Generated ONCE per logical reserve call, BEFORE any transaction, so every driver retry
     *  of that SAME call reuses the same id (see {@code Tx}'s multiple-invocation contract). */
    public static InventoryReservationId generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new InventoryReservationId("RESV_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
