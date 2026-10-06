package com.tazzzo.customer.order;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The delivery window an order reserved, snapshotted at placement. {@code serviceAreaId}, {@code windowId} and
 * {@code date} are INTERNAL (they are what cancellation needs to release the hold) and never reach a customer DTO;
 * {@code slotId}, {@code label}, {@code startsAt} and {@code endsAt} are what the customer sees. Absent on an order
 * placed without a slot (every order before this feature).
 */
public record OrderDeliverySlot(String serviceAreaId, String windowId, LocalDate date, String label, Instant startsAt,
                                Instant endsAt) {

    public OrderDeliverySlot {
        if (serviceAreaId == null || serviceAreaId.isBlank() || windowId == null || windowId.isBlank() || date == null
                || label == null || label.isBlank() || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("delivery slot snapshot incomplete");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("delivery slot must start before it ends");
        }
    }

    public String slotId() {
        return windowId + "~" + date;
    }
}
