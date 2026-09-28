package com.tazzzo.customer.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatchFieldTest {

    @Test void absent_is_not_present() {
        PatchField<String> field = PatchField.absent();
        assertThat(field.isPresent()).isFalse();
        assertThat(field.value()).isNull();
    }

    @Test void present_with_null_is_distinguishable_from_absent() {
        PatchField<String> presentNull = PatchField.of(null);
        PatchField<String> absent = PatchField.absent();
        assertThat(presentNull.isPresent()).isTrue();
        assertThat(presentNull.value()).isNull();
        assertThat(absent.isPresent()).isFalse();
        assertThat(presentNull).isNotEqualTo(absent);
    }

    @Test void present_with_value() {
        PatchField<String> field = PatchField.of("hello");
        assertThat(field.isPresent()).isTrue();
        assertThat(field.value()).isEqualTo("hello");
    }
}
