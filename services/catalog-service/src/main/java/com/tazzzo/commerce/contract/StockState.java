package com.tazzzo.commerce.contract;

/**
 * Consumer stock state (frozen /v1 contract). {@code UNKNOWN} is the safe default when no
 * location is supplied or inventory cannot be resolved — a purchasable decision is never made
 * on UNKNOWN. Do not add speculative values.
 */
public enum StockState {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK,
    UNKNOWN
}
