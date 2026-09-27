package com.tazzzo.auth.otp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-11B §36 phone test matrix. */
class PhoneTest {

    @ParameterizedTest
    @ValueSource(strings = {"+919876543210", "+916000000000", "+919999999999"})
    void valid_e164_india_is_accepted_verbatim(String raw) {
        assertThat(Phone.parse(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "6000000000"})
    void bare_ten_digit_domestic_form_is_normalized(String raw) {
        assertThat(Phone.parse(raw).value()).isEqualTo("+91" + raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"09876543210", "06000000000"})
    void leading_zero_domestic_form_is_normalized(String raw) {
        assertThat(Phone.parse(raw).value()).isEqualTo("+91" + raw.substring(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+91987654321",       // too short
            "+9198765432100",     // too long
            "+1919876543210",     // wrong country code
            "+91 9876543210",     // whitespace
            "+91-987-654-3210",   // hyphens
            "919876543210",       // missing +
            "+919876ABCDEF",      // letters
            "++919876543210",     // malformed prefix
            "+915876543210",      // leading digit 5, not a valid Indian mobile range
            "987654321",          // 9 bare digits
            "98765432100",        // 11 bare digits
            "",
            "   ",
    })
    void invalid_shapes_are_rejected(String raw) {
        assertThatThrownBy(() -> Phone.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_is_rejected() {
        assertThatThrownBy(() -> Phone.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversized_input_is_rejected_before_regex_work() {
        assertThatThrownBy(() -> Phone.parse("+91" + "9".repeat(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masked_never_reveals_the_full_number() {
        Phone phone = Phone.parse("+919876543210");
        assertThat(phone.masked()).isEqualTo("+91******3210");
        assertThat(phone.masked()).doesNotContain("9876543210");
    }

    @Test
    void canonical_constructor_rejects_non_canonical_values() {
        assertThatThrownBy(() -> new Phone("9876543210")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Phone(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
