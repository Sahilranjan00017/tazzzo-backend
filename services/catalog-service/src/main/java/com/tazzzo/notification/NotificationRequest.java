package com.tazzzo.notification;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * One notification to deliver to one customer about one subject. The recipient is the opaque customer id ONLY: the phone
 * number (or push token) is resolved by the provider adapter at send time, so the outbox never stores contact PII.
 * {@code params} are small, non-PII template values (order id, item count, amount), bounded in count and size.
 */
public record NotificationRequest(NotificationType type, String customerId, String subjectId, Map<String, String> params) {

    static final int MAX_PARAMS = 8;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    public NotificationRequest {
        if (type == null) throw new IllegalArgumentException("type required");
        if (customerId == null || !ID.matcher(customerId).matches()) throw new IllegalArgumentException("customerId invalid");
        if (subjectId == null || !ID.matcher(subjectId).matches()) throw new IllegalArgumentException("subjectId invalid");
        params = params == null ? Map.of() : Map.copyOf(params);
        if (params.size() > MAX_PARAMS) throw new IllegalArgumentException("too many params");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!KEY.matcher(e.getKey()).matches()) throw new IllegalArgumentException("param key invalid");
            String v = e.getValue();
            if (v.isEmpty() || v.length() > 64 || v.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                throw new IllegalArgumentException("param value invalid");
            }
        }
    }

    /** One notification per (type, subject), ever: a retried or replayed transaction never enqueues twice. */
    public String dedupeKey() {
        return type.name() + ":" + subjectId;
    }
}
