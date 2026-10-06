package com.tazzzo.delivery;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One recurring delivery window of a service area: local start/end minute of the day, how long before the start the
 * window stops accepting reservations (cutoff), how many deliveries it can take per day, and the ISO days of week it
 * runs (1 = Monday ... 7 = Sunday). The wall-clock times are interpreted in the configured delivery time zone, never
 * the server's.
 */
public record SlotWindow(String windowId, String label, int startMinute, int endMinute, int cutoffMinutes, int capacity,
                         Set<Integer> days) {

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    public static final int MAX_LABEL = 60;
    public static final int MAX_CAPACITY = 100_000;

    public SlotWindow {
        if (windowId == null || !ID.matcher(windowId).matches()) {
            throw new IllegalArgumentException("windowId must match [a-z0-9][a-z0-9-]{0,31}");
        }
        if (label == null || label.isBlank() || label.length() > MAX_LABEL || !label.equals(label.trim())
                || label.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException("label required: non-blank, trimmed, no control chars, max " + MAX_LABEL);
        }
        if (startMinute < 0 || endMinute > 1440 || startMinute >= endMinute) {
            throw new IllegalArgumentException("window must satisfy 0 <= startMinute < endMinute <= 1440");
        }
        if (cutoffMinutes < 0 || cutoffMinutes > 7 * 1440) {
            throw new IllegalArgumentException("cutoffMinutes must be between 0 and 10080");
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY);
        }
        if (days == null || days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
            throw new IllegalArgumentException("days must be a non-empty set of ISO weekdays 1..7");
        }
        days = java.util.Collections.unmodifiableSet(new TreeSet<>(days));
    }
}
