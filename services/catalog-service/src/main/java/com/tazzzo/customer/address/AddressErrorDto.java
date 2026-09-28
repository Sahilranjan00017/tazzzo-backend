package com.tazzzo.customer.address;

/** PR-12B — the flat public envelope for an address-domain failure (never an authentication
 *  failure -- those remain owned by {@code CustomerAuthFilter}). */
public record AddressErrorDto(String code, String message, String requestId) {
}
