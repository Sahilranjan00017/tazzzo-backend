package com.tazzzo.delivery;

import java.time.Instant;
import java.time.LocalDate;

/** The occurrence an order reserved: enough to snapshot on the order and to release the hold later. */
public record SlotChoice(String serviceAreaId, String windowId, LocalDate date, String label, Instant startsAt, Instant endsAt) {

    public String slotId() {
        return SlotOffer.slotId(windowId, date);
    }
}
