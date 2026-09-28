package com.tazzzo.customer.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisplayNamesTest {

    @Test void null_stays_null() {
        assertThat(DisplayNames.normalize(null)).isNull();
    }

    @Test void blank_after_trim_normalizes_to_null() {
        assertThat(DisplayNames.normalize("   ")).isNull();
    }

    @Test void surrounding_whitespace_is_trimmed() {
        assertThat(DisplayNames.normalize("  Sahil Ranjan  ")).isEqualTo("Sahil Ranjan");
    }

    @Test void legitimate_international_unicode_names_are_preserved() {
        assertThat(DisplayNames.normalize("Sahil Ranjan")).isEqualTo("Sahil Ranjan");
        assertThat(DisplayNames.normalize("Megha Namdeo")).isEqualTo("Megha Namdeo");
        assertThat(DisplayNames.normalize("José")).isEqualTo("José");
        assertThat(DisplayNames.normalize("李明")).isEqualTo("李明");
    }

    @Test void exactly_80_code_points_is_accepted() {
        String name = "x".repeat(80);
        assertThat(DisplayNames.normalize(name)).isEqualTo(name);
    }

    @Test void over_80_code_points_is_rejected() {
        assertThatThrownBy(() -> DisplayNames.normalize("x".repeat(81)))
                .isInstanceOf(CustomerProfileFailure.class);
    }

    @Test void a_control_character_is_rejected() {
        String withControl = "Sahil" + '\u0001' + "Ranjan";
        assertThatThrownBy(() -> DisplayNames.normalize(withControl)).isInstanceOf(CustomerProfileFailure.class);
    }
}
