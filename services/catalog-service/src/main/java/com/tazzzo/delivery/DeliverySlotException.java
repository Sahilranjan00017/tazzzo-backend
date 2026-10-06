package com.tazzzo.delivery;

/** Typed failures of the delivery-slot admin surface. Messages are internal-grade but contain no personal data. */
public abstract class DeliverySlotException extends RuntimeException {
    protected DeliverySlotException(String message) { super(message); }

    public static final class Invalid extends DeliverySlotException {
        public Invalid(String message) { super(message); }
    }

    public static final class NotFound extends DeliverySlotException {
        public NotFound(String message) { super(message); }
    }

    public static final class Conflict extends DeliverySlotException {
        public Conflict(String message) { super(message); }
    }
}
