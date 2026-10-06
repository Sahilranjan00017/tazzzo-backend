package com.tazzzo.support;

import java.time.Instant;
import java.util.List;

/** One support case: a customer's thread with staff. Free text is PERSONAL DATA (see the retention matrix). */
public record SupportCase(String caseId, String customerId, Category category, String orderId, String subject, Status status,
                          List<Message> messages, String assignedTo, long version, Instant createdAt, Instant updatedAt) {

    public enum Category { ORDER_ISSUE, DELIVERY, PRODUCT, ACCOUNT, OTHER }

    public enum Status { OPEN, IN_PROGRESS, RESOLVED, CLOSED;
        public boolean isOpen() { return this == OPEN || this == IN_PROGRESS; }
    }

    public enum Author { CUSTOMER, STAFF }

    public record Message(int id, Author author, String staffId, String text, Instant at) { }

    public static final int MAX_MESSAGES = 100;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_SUBJECT = 120;
    public static final int MAX_OPEN_PER_CUSTOMER = 5;

    public SupportCase {
        messages = List.copyOf(messages);
    }

    /** Plain text: trimmed, 1..max chars, no control characters except newline. */
    public static String cleanText(String raw, int max) {
        if (raw == null) {
            throw new IllegalArgumentException("text required");
        }
        String t = raw.strip();
        if (t.isEmpty() || t.length() > max || t.chars().anyMatch(c -> (c < 0x20 && c != '\n') || c == 0x7F)) {
            throw new IllegalArgumentException("text must be 1.." + max + " chars of plain text");
        }
        return t;
    }
}
