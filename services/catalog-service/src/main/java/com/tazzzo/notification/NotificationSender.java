package com.tazzzo.notification;

/**
 * The provider port (SMS / WhatsApp / push). The adapter resolves the customer's contact from {@code customerId} at send
 * time and maps {@code type} to its own approved template. Implementations must not log contact details or params.
 */
public interface NotificationSender {

    enum Outcome {
        /** The provider accepted it. */
        SENT,
        /** Transient (timeout, 5xx, throttled): try again later. */
        RETRY,
        /** Permanent (no contact on file, opted out, template refused): never retried. */
        REJECTED
    }

    Outcome send(OutboxNotification notification);

    /** Whether this sender actually delivers; the dispatcher refuses to start on one that does not. */
    default boolean delivers() {
        return true;
    }
}
