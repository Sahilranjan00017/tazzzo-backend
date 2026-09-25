package com.tazzzo.commerce.contract;

/**
 * Frozen public error codes (ADR-010). These are the only codes the /v1 surface returns.
 * Do not add speculative values.
 */
public enum PublicErrorCode {
    INVALID_REQUEST,
    INVALID_CURSOR,
    NOT_FOUND,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    INTERNAL
}
