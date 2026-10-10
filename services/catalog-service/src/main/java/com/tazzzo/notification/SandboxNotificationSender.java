package com.tazzzo.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * DEV/TEST ONLY, credential-free sender ({@code tazzzo.notifications.provider=sandbox}). It performs no network I/O and
 * reaches no customer: every notification is "delivered" into a bounded in-memory record so the whole outbox lifecycle
 * (enqueue, claim, send, complete, backoff, terminal states, metrics) can run end to end without a vendor. It is refused
 * at startup outside an unset/local/test/dev environment (see {@link NotificationProviderSelector}): in a real environment
 * it would mark customer messages SENT while nobody receives them.
 *
 * <p>Always answers {@code SENT}. Failure injection exists ONLY by subclassing {@link #decide} in a test; there is no
 * production property for it. Logs carry the type, attempt and outcome only: never contact data, params or message text
 * (the sandbox has none of those: the recipient is an opaque id and params are never printed).
 */
public class SandboxNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SandboxNotificationSender.class);
    static final int RECENT_LIMIT = 100;

    /** One recorded send: notification id, attempt and outcome. No recipient, no params. */
    public record Delivery(String notificationId, int attempt, Outcome outcome) { }

    private final Deque<Delivery> recent = new ArrayDeque<>();
    private final Map<Outcome, Long> totals = new EnumMap<>(Outcome.class);

    /** Test hook: the outcome for this notification. The default delivers everything. */
    protected Outcome decide(OutboxNotification notification) {
        return Outcome.SENT;
    }

    @Override
    public final Outcome send(OutboxNotification notification) {
        Outcome outcome = decide(notification);
        synchronized (this) {
            totals.merge(outcome, 1L, Long::sum);
            recent.addLast(new Delivery(notification.id(), notification.attempt(), outcome));
            while (recent.size() > RECENT_LIMIT) {
                recent.removeFirst();
            }
        }
        log.info("notification_sandbox type={} attempt={} outcome={}", notification.type(), notification.attempt(), outcome);
        return outcome;
    }

    /** The most recent sends (at most {@value #RECENT_LIMIT}), oldest first. */
    public synchronized List<Delivery> recent() {
        return List.copyOf(recent);
    }

    public synchronized long total(Outcome outcome) {
        return totals.getOrDefault(outcome, 0L);
    }
}
