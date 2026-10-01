package com.tazzzo.benefits;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BenefitsFailureTest {

    @Test
    void the_failure_vocabulary_holds_only_reasons_with_a_current_runtime_producer() {
        assertThat(Arrays.stream(BenefitsFailure.Reason.values()).map(Enum::name).toList())
                .containsExactly("INVALID_REQUEST", "UNAVAILABLE", "INTEGRITY_FAILURE");
    }

    @Test
    void the_no_benefit_vocabulary_is_unchanged_and_separate_from_failures() {
        assertThat(Arrays.stream(BenefitEvaluation.NoBenefitReason.values()).map(Enum::name).toList())
                .containsExactly("NO_MEMBERSHIP", "NO_RULE", "NOT_ELIGIBLE");
    }
}
