package com.tazzzo.notification;

/**
 * The closed set of customer notifications. Each maps to a provider template chosen outside the code (DLT template ids,
 * push channels: an external decision). Adding a type is a code change, never free text.
 */
public enum NotificationType {
    /** A COD order was placed and confirmed. Subject: the order id. */
    ORDER_CONFIRMED,
    /** Staff marked the order out for delivery. Subject: the order id (one per order). */
    ORDER_OUT_FOR_DELIVERY,
    /** Staff marked the order delivered. Subject: the order id (one per order). */
    ORDER_DELIVERED,
    /** The order was cancelled, by the customer or by staff. Subject: the order id (an order is cancelled at most once). */
    ORDER_CANCELLED,
    /** Staff replied on a support case. Subject: {@code <caseId>-m<message number>} (one per reply). */
    SUPPORT_REPLY,
    /** Staff resolved a support case. Subject: {@code <caseId>-v<case version>} (a case may be resolved again after a reopen). */
    SUPPORT_CASE_RESOLVED
}
