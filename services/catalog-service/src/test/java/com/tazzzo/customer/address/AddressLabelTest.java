package com.tazzzo.customer.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressLabelTest {

    @Test void accepts_the_closed_vocabulary_case_insensitively() {
        assertThat(AddressLabel.parse("HOME")).isEqualTo(AddressLabel.HOME);
        assertThat(AddressLabel.parse("work")).isEqualTo(AddressLabel.WORK);
        assertThat(AddressLabel.parse("Other")).isEqualTo(AddressLabel.OTHER);
    }

    @Test void rejects_anything_outside_the_vocabulary() {
        assertThatThrownBy(() -> AddressLabel.parse("SCHOOL")).isInstanceOf(AddressFailure.class);
        assertThatThrownBy(() -> AddressLabel.parse(null)).isInstanceOf(AddressFailure.class);
    }
}
