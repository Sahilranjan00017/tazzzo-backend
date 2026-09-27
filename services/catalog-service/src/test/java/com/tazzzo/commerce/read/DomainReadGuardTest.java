package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-10B final review #9 (extended PR-10C #7) — the ONE translation point from a domain read
 * port's typed failure (or a datastore outage) to the public {@code SERVICE_UNAVAILABLE}, pinned
 * per domain via {@link CommerceReadUnavailableException.Category} so a Pricing, Inventory, Media,
 * Serviceability or Mongo "outage" maps to 503 with a bounded, closed-vocabulary classification —
 * never an unmapped 500 — while a genuinely unexpected programming failure is left completely
 * untouched.
 */
class DomainReadGuardTest {

    @Test void a_pricing_outage_becomes_unavailable_with_the_pricing_category() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.pricing.InvalidPriceException("simulated pricing outage");
        })).isInstanceOfSatisfying(CommerceReadUnavailableException.class,
                e -> assertThat(e.category()).isEqualTo(CommerceReadUnavailableException.Category.PRICING));
    }

    @Test void an_inventory_outage_becomes_unavailable_with_the_inventory_category() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.inventory.InvalidInventoryException("simulated inventory outage");
        })).isInstanceOfSatisfying(CommerceReadUnavailableException.class,
                e -> assertThat(e.category()).isEqualTo(CommerceReadUnavailableException.Category.INVENTORY));
    }

    @Test void a_serviceability_outage_becomes_unavailable_with_the_serviceability_category() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.serviceability.InvalidServiceabilityException("simulated serviceability outage");
        })).isInstanceOfSatisfying(CommerceReadUnavailableException.class,
                e -> assertThat(e.category()).isEqualTo(CommerceReadUnavailableException.Category.SERVICEABILITY));
    }

    @Test void media_corruption_becomes_unavailable_with_the_media_category() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.media.InvalidMediaException("simulated corrupt assetKey");
        })).isInstanceOfSatisfying(CommerceReadUnavailableException.class,
                e -> assertThat(e.category()).isEqualTo(CommerceReadUnavailableException.Category.MEDIA));
    }

    @Test void a_datastore_outage_becomes_unavailable_with_the_mongo_category() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.mongodb.MongoException("simulated network partition");
        })).isInstanceOfSatisfying(CommerceReadUnavailableException.class,
                e -> assertThat(e.category()).isEqualTo(CommerceReadUnavailableException.Category.MONGO));
    }

    @Test void the_generic_message_never_carries_the_underlying_exception_text() {
        assertThatThrownBy(() -> DomainReadGuard.guard(() -> {
            throw new com.tazzzo.pricing.InvalidPriceException("selling_price_paise for sku TZP-SECRET-1");
        })).isInstanceOf(CommerceReadUnavailableException.class)
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
