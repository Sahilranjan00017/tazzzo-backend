package com.tazzzo.customer.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressTextsTest {

    @Test void required_trims_and_accepts_unicode() {
        assertThat(AddressTexts.required("  José  ", 80)).isEqualTo("José");
        assertThat(AddressTexts.required("李明", 80)).isEqualTo("李明");
    }

    @Test void required_rejects_blank() {
        assertThatThrownBy(() -> AddressTexts.required("   ", 80)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> AddressTexts.required(null, 80)).isInstanceOf(AddressFailure.class);
    }

    @Test void required_rejects_over_max_and_control_chars() {
        assertThatThrownBy(() -> AddressTexts.required("x".repeat(161), 160)).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> AddressTexts.required("a" + '\u0007' + "b", 80))
                .isInstanceOf(AddressFailure.class);
    }

    @Test void optional_null_stays_null_and_blank_normalizes_to_null() {
        assertThat(AddressTexts.optional(null, 80)).isNull();
        assertThat(AddressTexts.optional("   ", 80)).isNull();
    }

    @Test void optional_valid_value_is_trimmed() {
        assertThat(AddressTexts.optional("  Near mall  ", 120)).isEqualTo("Near mall");
    }
}
