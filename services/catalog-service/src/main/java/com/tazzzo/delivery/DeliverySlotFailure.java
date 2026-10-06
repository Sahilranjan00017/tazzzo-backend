package com.tazzzo.delivery;

/** A customer-surface refusal; the message is deliberately generic. */
public class DeliverySlotFailure extends RuntimeException {

    public enum Reason { INVALID_REQUEST, UNAVAILABLE }

    private final Reason reason;

    public DeliverySlotFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
