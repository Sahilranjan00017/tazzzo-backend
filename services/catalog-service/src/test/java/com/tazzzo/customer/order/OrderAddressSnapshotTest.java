package com.tazzzo.customer.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-15A-2 — the snapshot's coordinate contract mirrors the Address domain (the authority): a pair is
 * BOTH absent or BOTH present, finite and in range. Protected here, below any controller or Mongo.
 */
class OrderAddressSnapshotTest {

    private static OrderAddressSnapshot snap(Double lat, Double lon) {
        return new OrderAddressSnapshot("HOME", "Ravi", "+919876500001", "12 MG Road", null, null, "Bengaluru",
                "Karnataka", "560001", lat, lon);
    }

    @Test void both_coordinates_absent_is_valid_and_stays_null() {
        OrderAddressSnapshot s = snap(null, null);
        assertThat(s.latitude()).isNull();
        assertThat(s.longitude()).isNull();
    }

    @Test void both_coordinates_present_and_valid_is_preserved_exactly() {
        OrderAddressSnapshot s = snap(12.9716, 77.5946);
        assertThat(s.latitude()).isEqualTo(12.9716);
        assertThat(s.longitude()).isEqualTo(77.5946);
    }

    @Test void the_range_boundaries_are_valid() {
        snap(90.0, 180.0);
        snap(-90.0, -180.0);
        snap(0.0, 0.0); // a REAL (0,0) is a valid supplied pair; it is never an invented default
    }

    @Test void exactly_one_coordinate_is_corrupt_state_and_fails_loud() {
        assertThatThrownBy(() -> snap(12.9716, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both present or both absent");
        assertThatThrownBy(() -> snap(null, 77.5946)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both present or both absent");
    }

    @Test void out_of_range_coordinates_fail_loud() {
        assertThatThrownBy(() -> snap(90.0001, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snap(-90.0001, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snap(0.0, 180.0001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snap(0.0, -180.0001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void non_finite_coordinates_fail_loud() {
        assertThatThrownBy(() -> snap(Double.NaN, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snap(0.0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snap(Double.POSITIVE_INFINITY, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snap(0.0, Double.NEGATIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void the_other_required_fields_are_still_enforced() {
        assertThatThrownBy(() -> new OrderAddressSnapshot(" ", "Ravi", "+91", "L1", null, null, "C", "S", "560001",
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderAddressSnapshot("HOME", "Ravi", "+91", "L1", null, null, "C", "S", null,
                null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
