package com.tazzzo.notification;

/**
 * The default: no provider is configured (an external decision: DLT-registered SMS templates, a WhatsApp BSP, or FCM).
 * Rows stay PENDING and expire by TTL; the dispatcher refuses to start against this sender.
 */
public final class DisabledNotificationSender implements NotificationSender {

    @Override
    public Outcome send(OutboxNotification notification) {
        throw new IllegalStateException("no notification provider is configured");
    }

    @Override
    public boolean delivers() {
        return false;
    }
}
