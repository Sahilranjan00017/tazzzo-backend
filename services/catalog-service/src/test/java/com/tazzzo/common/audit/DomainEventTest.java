package com.tazzzo.common.audit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PR-06 review STEP 5: defensive detail copy + technical identity bounds. */
class DomainEventTest {

    @Test void detail_is_defensively_copied_and_immutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v1");
        DomainEvent e = new DomainEvent("serviceability_pin", "560047", "SERVICE_AREA_UPDATED", mutable);
        mutable.put("k", "TAMPERED");
        mutable.put("extra", "TAMPERED");
        assertEquals("v1", e.detail().get("k"), "post-construction caller mutation must not alter the event");
        assertFalse(e.detail().containsKey("extra"));
        assertThrows(UnsupportedOperationException.class, () -> e.detail().put("x", "y"));
    }

    @Test void null_detail_becomes_empty() {
        assertTrue(new DomainEvent("t", "id", "TYPE", null).detail().isEmpty());
    }

    @Test void identity_fields_bounded_and_clean() {
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent(" ", "id", "TYPE", null));
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent("t", "", "TYPE", null));
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent("t", "id", " ", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DomainEvent("x".repeat(201), "id", "TYPE", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DomainEvent("t", "a\tb", "TYPE", null));
        assertDoesNotThrow(() -> new DomainEvent("x".repeat(200), "id", "TYPE", null));
    }
}
