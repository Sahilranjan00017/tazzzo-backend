package com.tazzzo.customer.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinatesTest {

    @Test void both_absent_is_accepted() {
        Coordinates.Pair p = Coordinates.validate(null, null);
        assertThat(p.latitude()).isNull();
        assertThat(p.longitude()).isNull();
    }

    @Test void valid_pair_is_accepted() {
        Coordinates.Pair p = Coordinates.validate(12.9, 77.6);
        assertThat(p.latitude()).isEqualTo(12.9);
        assertThat(p.longitude()).isEqualTo(77.6);
    }

    @Test void only_latitude_is_rejected() {
        assertThatThrownBy(() -> Coordinates.validate(12.9, null)).isInstanceOf(AddressFailure.class);
    }

    @Test void only_longitude_is_rejected() {
        assertThatThrownBy(() -> Coordinates.validate(null, 77.6)).isInstanceOf(AddressFailure.class);
    }

    @Test void latitude_out_of_range_is_rejected() {
        assertThatThrownBy(() -> Coordinates.validate(90.1, 0.0)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> Coordinates.validate(-90.1, 0.0)).isInstanceOf(AddressFailure.class);
    }

    @Test void longitude_out_of_range_is_rejected() {
        assertThatThrownBy(() -> Coordinates.validate(0.0, 180.1)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> Coordinates.validate(0.0, -180.1)).isInstanceOf(AddressFailure.class);
    }

    @Test void boundary_values_are_accepted() {
        Coordinates.validate(90.0, 180.0);
        Coordinates.validate(-90.0, -180.0);
    }

    @Test void nan_and_infinity_are_rejected() {
        assertThatThrownBy(() -> Coordinates.validate(Double.NaN, 0.0)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> Coordinates.validate(0.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> Coordinates.validate(Double.NEGATIVE_INFINITY, 0.0))
                .isInstanceOf(AddressFailure.class);
    }
}
