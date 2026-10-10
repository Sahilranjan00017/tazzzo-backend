package com.tazzzo.commerce.read;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cap is validated when the service is built: outside 1..50 the application does not start. */
class CommerceProductBatchServiceTest {

    @Test
    void the_hard_maximum_is_the_enrichers_page_bound_and_the_default_is_the_maximum() {
        assertThat(CommerceProductBatchService.HARD_MAX_IDS).isEqualTo(50);
        assertThat(CommerceProductBatchService.DEFAULT_MAX_IDS).isEqualTo(50);
    }

    @Test
    void a_cap_outside_one_to_fifty_is_a_startup_failure() {
        for (int bad : new int[]{Integer.MIN_VALUE, -1, 0, 51, 100, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new CommerceProductBatchService(null, null, null, null, null, null, null, bad))
                    .as("max-ids " + bad).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tazzzo.commerce.product-batch.max-ids");
        }
    }
}
