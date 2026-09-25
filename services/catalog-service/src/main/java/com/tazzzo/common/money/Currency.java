package com.tazzzo.common.money;

/**
 * Supported settlement currencies. Phase 1 is INR-only (ADR-002); the enum exists so that
 * {@link Money} is currency-qualified from day one and multi-currency is an additive change,
 * not a refactor. Do not add speculative values.
 */
public enum Currency {
    INR
}
