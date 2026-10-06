package com.tazzzo.notification;

import java.time.Instant;
import java.util.Map;

/** A claimed outbox row as the sender sees it. {@code attempt} is 1 for the first try. */
public record OutboxNotification(String id, NotificationType type, String customerId, String subjectId,
                                 Map<String, String> params, int attempt, Instant createdAt) { }
