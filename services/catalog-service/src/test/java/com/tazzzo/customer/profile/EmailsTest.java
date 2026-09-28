package com.tazzzo.customer.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailsTest {

    @Test void null_stays_null() {
        assertThat(Emails.normalize(null)).isNull();
    }

    @Test void blank_after_trim_normalizes_to_null() {
        assertThat(Emails.normalize("   ")).isNull();
    }

    @Test void valid_email_is_trimmed_and_lower_cased() {
        assertThat(Emails.normalize("  Sahil@Example.COM  ")).isEqualTo("sahil@example.com");
    }

    @Test void malformed_shape_is_rejected() {
        assertThatThrownBy(() -> Emails.normalize("not-an-email")).isInstanceOf(CustomerProfileFailure.class);
        assertThatThrownBy(() -> Emails.normalize("@example.com")).isInstanceOf(CustomerProfileFailure.class);
        assertThatThrownBy(() -> Emails.normalize("sahil@")).isInstanceOf(CustomerProfileFailure.class);
        assertThatThrownBy(() -> Emails.normalize("sahil@example")).isInstanceOf(CustomerProfileFailure.class);
    }

    @Test void internal_whitespace_is_rejected() {
        assertThatThrownBy(() -> Emails.normalize("sahil ranjan@example.com"))
                .isInstanceOf(CustomerProfileFailure.class);
    }

    @Test void over_254_characters_is_rejected() {
        String local = "a".repeat(250);
        assertThatThrownBy(() -> Emails.normalize(local + "@example.com"))
                .isInstanceOf(CustomerProfileFailure.class);
    }

    @Test void control_character_is_rejected() {
        String withControl = "sahil" + '\u0001' + "@example.com";
        assertThatThrownBy(() -> Emails.normalize(withControl)).isInstanceOf(CustomerProfileFailure.class);
    }
}
