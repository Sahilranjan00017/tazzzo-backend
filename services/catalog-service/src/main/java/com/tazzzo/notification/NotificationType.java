package com.tazzzo.notification;

/**
 * The closed set of customer notifications. Each maps to a provider template chosen outside the code (DLT template ids,
 * push channels: an external decision). Adding a type is a code change, never free text.
 */
public enum NotificationType {
    /** A COD order was placed and confirmed. Subject: the order id. */
    ORDER_CONFIRMED
}
