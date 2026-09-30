package com.tazzzo.customer.order;

/** PR-15A-2 — the safe, generic error body: {@code {code, message, requestId}}, nothing else. */
public record OrderErrorDto(String code, String message, String requestId) {
}
