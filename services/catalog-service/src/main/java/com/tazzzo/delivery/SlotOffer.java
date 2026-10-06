package com.tazzzo.delivery;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/** One bookable occurrence (window x date) as a customer sees it. {@code remaining} is internal-grade detail kept off the wire. */
public record SlotOffer(String slotId, String windowId, String label, LocalDate date, ZonedDateTime startsAt,
                        ZonedDateTime endsAt, Status status, int remaining) {

    public enum Status { AVAILABLE, FULL, CLOSED }

    public static String slotId(String windowId, LocalDate date) {
        return windowId + "~" + date;
    }
}
