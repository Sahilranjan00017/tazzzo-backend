package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-10B final review #9 — the ONE translation point from a domain read port's typed failure (or a
 * datastore outage) to the public {@code SERVICE_UNAVAILABLE}, pinned per domain so a Pricing,
 * Inventory, Media or Serviceability "outage" maps to 503, never an unmapped 500 — while a
 * genuinely unexpected programming failure is left completely untouched.
 */
class DomainReadGuardTest {

    @Test void a_pricing_outage_becomes_unavailable() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.pricing.InvalidPriceException("simulated pricing outage");
        })).isInstanceOf(ConsumerFailures.Unavailable.class);
    }

    @Test void an_inventory_outage_becomes_unavailable() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.inventory.InvalidInventoryException("simulated inventory outage");
        })).isInstanceOf(ConsumerFailures.Unavailable.class);
    }

    @Test void a_serviceability_outage_becomes_unavailable() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.serviceability.InvalidServiceabilityException("simulated serviceability outage");
        })).isInstanceOf(ConsumerFailures.Unavailable.class);
    }

    @Test void media_corruption_becomes_unavailable() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.media.InvalidMediaException("simulated corrupt assetKey");
        })).isInstanceOf(ConsumerFailures.Unavailable.class);
    }

    @Test void a_datastore_outage_becomes_unavailable() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.mongodb.MongoException("simulated network partition");
        })).isInstanceOf(ConsumerFailures.Unavailable.class);
    }

    @Test void the_generic_message_never_carries_the_underlying_exception_text() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.pricing.InvalidPriceException("selling_price_paise for sku TZP-SECRET-1");
        })).isInstanceOf(ConsumerFailures.Unavailable.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                        .doesNotContain("TZP-SECRET-1"));
    }

    @Test void a_genuinely_unexpected_programming_failure_is_never_reclassified() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new NullPointerException("adapter bug");
        })).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new IllegalStateException("adapter bug");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test void an_already_typed_consumer_failure_passes_through_unchanged() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new ConsumerFailures.NotFound("not found");
        })).isInstanceOf(ConsumerFailures.NotFound.class);
    }
}
