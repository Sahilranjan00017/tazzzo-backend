package com.tazzzo.pricing;

import java.util.Optional;

/**
 * Result of a current-price read (STEP 11). Carries the resolved {@link PriceStatus} and, when a
 * row exists, the {@link Price} — a pricing-domain value, never an API DTO. A caller must treat
 * anything other than {@link PriceStatus#ACTIVE} as "no usable price".
 */
public record PriceLookup(PriceStatus status, Price price) {

    public static PriceLookup missing() {
        return new PriceLookup(PriceStatus.MISSING, null);
    }

    public static PriceLookup of(PriceStatus status, Price price) {
        return new PriceLookup(status, price);
    }

    public boolean isUsable() {
        return status == PriceStatus.ACTIVE && price != null;
    }

    public Optional<Price> usablePrice() {
        return isUsable() ? Optional.of(price) : Optional.empty();
    }
}
