package com.tazzzo.catalog.domain;

/**
 * F-5 — the multipack link. A variant_pack is the SAME product in a different presentation
 * (6 x 250ml), so it points at exactly one component with a quantity. Contrast BundleComponent:
 * a bundle is DIFFERENT products together and carries a list.
 *
 * qty >= 2 by contract: a "multipack of 1" is a single, and permitting it would give one
 * product two encodings — the identity fork U-4-g exists to prevent.
 */
public record PackOf(String componentProductId, int qty) { }
