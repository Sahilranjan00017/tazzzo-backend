package com.tazzzo.commerce.read;

import java.util.List;
import java.util.Objects;

/**
 * INTERNAL result of enriching one page of base cards for one request (PR-08, STEP 18).
 * Card composition only — cursor/release/requestId pagination ownership stays with the existing
 * catalog list machinery (LIST-1/PAG-2) and joins in the public API PR; nothing here duplicates
 * it. {@code serviceArea} is null for anonymous browse and for unserviceable PINs with no
 * configured area.
 */
public record RuntimeProductPage(List<RuntimeProductCard> cards, RuntimeServiceArea serviceArea) {

    public RuntimeProductPage {
        Objects.requireNonNull(cards, "cards required");
        cards = List.copyOf(cards);
    }
}
