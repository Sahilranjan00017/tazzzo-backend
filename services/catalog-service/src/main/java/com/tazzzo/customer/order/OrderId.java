package com.tazzzo.customer.order;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-14B — an opaque, CSPRNG-generated order identity ({@code ORD_...}). Mirrors
 * {@code AddressId}/{@code CheckoutQuoteId}/{@code InventoryReservationId}: never sequential,
 * never derived from any input, bounded length, conservative alphabet.
 */
public record OrderId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^ORD_[A-Za-z0-9_-]{6,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public OrderId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid order id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** Generated ONCE per create attempt, BEFORE entering any transaction, so a transaction retry
     *  reuses the SAME id rather than minting a new one on every attempt. */
    public static OrderId generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new OrderId("ORD_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
