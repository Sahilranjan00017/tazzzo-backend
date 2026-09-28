package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** PR-13A — safe, generic error body; {@code items} only for CHECKOUT_ITEM_UNAVAILABLE. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutErrorDto(String code, String message, String requestId, List<Item> items) {

    public record Item(String skuId, String reason) {
    }
}
