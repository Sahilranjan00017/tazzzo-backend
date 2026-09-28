package com.tazzzo.customer.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientPhoneTest {

    @Test void accepts_e164_and_domestic_shorthands() {
        assertThat(RecipientPhone.normalize("+919876500001")).isEqualTo("+919876500001");
        assertThat(RecipientPhone.normalize("9876500001")).isEqualTo("+919876500001");
        assertThat(RecipientPhone.normalize("09876500001")).isEqualTo("+919876500001");
    }

    @Test void rejects_malformed_shapes() {
        assertThatThrownBy(() -> RecipientPhone.normalize(null)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> RecipientPhone.normalize("12345")).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> RecipientPhone.normalize("+15551234567")).isInstanceOf(AddressFailure.class);
    }
}
