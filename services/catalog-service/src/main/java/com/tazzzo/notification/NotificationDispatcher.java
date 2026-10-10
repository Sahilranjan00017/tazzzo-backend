package com.tazzzo.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Drains due outbox rows through the {@link NotificationSender}. A row older than {@code maxAge} is EXPIRED unsent ("your
 * order is confirmed" a day later is noise). RETRY backs off exponentially from {@code baseBackoff} (capped at 30 minutes)
 * and becomes FAILED after {@code maxAttempts}; REJECTED fails at once; a throwing sender counts as RETRY. Logs carry the
 * row id's type prefix and the outcome only: never the customer id, subject or params.
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final NotificationOutbox outbox;
    private final NotificationSender sender;
    private final Clock clock;
    private final MeterRegistry registry;
    private final Duration lease;
    private final Duration maxAge;
    private final Duration baseBackoff;
    private final int maxAttempts;

    public NotificationDispatcher(NotificationOutbox outbox, NotificationSender sender, Clock clock, MeterRegistry registry,
                                  Duration lease, Duration maxAge, Duration baseBackoff, int maxAttempts) {
        if (!sender.delivers()) {
            throw new IllegalStateException("notification dispatch is enabled but no provider is configured");
        }
        if (lease.isNegative() || lease.isZero() || maxAge.isNegative() || maxAge.isZero() || baseBackoff.isNegative()
                || baseBackoff.isZero() || maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalStateException("notification dispatch settings out of range");
        }
        this.outbox = outbox;
        this.sender = sender;
        this.clock = clock;
        this.registry = registry;
        this.lease = lease;
        this.maxAge = maxAge;
        this.baseBackoff = baseBackoff;
        this.maxAttempts = maxAttempts;
    }

    /** @return how many rows were claimed this tick (at most {@code batch}). */
    public int dispatchDue(int batch) {
        int claimed = 0;
        while (claimed < batch) {
            Optional<NotificationOutbox.Claim> next = outbox.claimNext(lease);
            if (next.isEmpty()) {
                break;
            }
            claimed++;
            handle(next.get());
        }
        return claimed;
    }

    private void handle(NotificationOutbox.Claim c) {
        OutboxNotification n = c.notification();
        Instant now = clock.instant();
        if (n.createdAt().plus(maxAge).isBefore(now)) {
            record(n, outbox.complete(c, "EXPIRED") ? "expired" : "claim_lost");
            return;
        }
        NotificationSender.Outcome outcome;
        try {
            outcome = sender.send(n);
        } catch (RuntimeException e) {
            outcome = NotificationSender.Outcome.RETRY;
        }
        boolean held = switch (outcome) {
            case SENT -> outbox.complete(c, "SENT");
            case REJECTED -> outbox.complete(c, "FAILED");
            case RETRY -> n.attempt() >= maxAttempts ? outbox.complete(c, "FAILED") : outbox.retryAt(c, now.plus(backoff(n.attempt())));
        };
        String tag = switch (outcome) {
            case SENT -> "sent";
            case REJECTED -> "rejected";
            case RETRY -> n.attempt() >= maxAttempts ? "failed" : "retry";
        };
        record(n, held ? tag : "claim_lost");
    }

    Duration backoff(int attempt) {
        Duration d = baseBackoff.multipliedBy(1L << Math.min(attempt - 1, 20));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private void record(OutboxNotification n, String outcome) {
        if (!"sent".equals(outcome)) {
            log.info("notification_dispatch type={} outcome={} attempt={}", n.type(), outcome, n.attempt());
        }
        try {
            if ("sent".equals(outcome)) {
                // enqueue -> provider accepted; a created_at ahead of this clock (cross-instance skew) counts as zero
                Duration latency = Duration.between(n.createdAt(), clock.instant());
                Timer.builder("notification_dispatch_latency").tag("type", n.type().name()).register(registry)
                        .record(latency.compareTo(Duration.ZERO) < 0 ? Duration.ZERO : latency);
            }
            Counter.builder("notification_dispatch").tag("type", n.type().name()).tag("outcome", outcome)
                    .register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("notification metric failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
