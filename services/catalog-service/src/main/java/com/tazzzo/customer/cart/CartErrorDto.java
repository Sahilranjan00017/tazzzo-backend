package com.tazzzo.customer.cart;

/** PR-12C — flat public error envelope (domain failures only; auth stays CustomerAuthFilter's). */
public record CartErrorDto(String code, String message, String requestId) {
}
