package com.tazzzo.customer.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostalCodeTest {

    @Test void accepts_a_valid_indian_pin() {
        assertThat(PostalCode.normalize(" 560047 ")).isEqualTo("560047");
    }

    @Test void rejects_malformed_pins() {
        assertThatThrownBy(() -> PostalCode.normalize(null)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> PostalCode.normalize("12345")).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> PostalCode.normalize("0123456")).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> PostalCode.normalize("abcdef")).isInstanceOf(AddressFailure.class);
    }
}
