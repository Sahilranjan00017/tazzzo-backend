package com.tazzzo.customer.checkout;

import java.util.List;

/**
 * PR-13A — every way a checkout request can fail for reasons OWNED by this domain: one typed
 * exception carrying a closed {@link Reason} (and, for {@code CHECKOUT_ITEM_UNAVAILABLE}, the
 * bounded per-line rejections). Authentication stays CustomerAuthFilter's concern (401).
 * {@code NOT_FOUND} deliberately covers an unknown/foreign address and an unknown/foreign quote.
 */
public final class CheckoutFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, UNSUPPORTED_MEDIA_TYPE, NOT_FOUND, PRECONDITION_REQUIRED, PRECONDITION_FAILED,
        IDEMPOTENCY_REQUIRED, IDEMPOTENCY_CONFLICT, CHECKOUT_CART_EMPTY, CHECKOUT_ITEM_UNAVAILABLE,
        CHECKOUT_UNSERVICEABLE, QUOTE_EXPIRED, UNAVAILABLE
    }

    public record ItemRejection(String skuId, CheckoutItemReason reason) {
    }

    private final Reason reason;
    private final List<ItemRejection> rejections;

    public CheckoutFailure(Reason reason) {
        this(reason, List.of());
    }

    public CheckoutFailure(Reason reason, List<ItemRejection> rejections) {
        super(reason.name());
        this.reason = reason;
        this.rejections = List.copyOf(rejections);
    }

    public Reason reason() {
        return reason;
    }

    public List<ItemRejection> rejections() {
        return rejections;
    }
}
