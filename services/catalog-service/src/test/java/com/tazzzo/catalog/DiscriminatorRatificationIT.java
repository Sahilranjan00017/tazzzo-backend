package com.tazzzo.catalog;

import com.tazzzo.catalog.schema.CanonicalKeyService;
import com.tazzzo.catalog.schema.CanonicalKey;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import com.tazzzo.catalog.schema.Ratification;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-F1 — the two tests whose ABSENCE let the gap through.
 *
 * derive() currently consults the ratified list only as a yes/no gate and then builds the key
 * from pack alone. So a richer ratification is silently ignored, and identity degrades to
 * pack-only — the C-3 failure returning as under-discrimination.
 *
 * Written against the CURRENT api deliberately, so the failure is demonstrated before any
 * implementation change.
 */
class DiscriminatorRatificationIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader taxonomyLoader;
    @Autowired CanonicalKeyService canonicalKeyService;
    @Autowired DiscriminatingAttributeRegistry registry;

    /**
     * A DIFFERENT vertical from CanonicalKeyIT's, as prophylaxis rather than as a fix for an
     * observed failure. DiscriminatingAttributeRegistry is a singleton in a Spring context shared
     * across test classes, so two classes ratifying the same vertical would depend on @BeforeAll
     * ordering; separate verticals make the suites order-independent by construction.
     *
     * In production the hazard does not arise at all: ratifications load once at bootstrap from
     * discriminating_attributes (W-F2) and are never mutated at runtime.
     */
    static final String V = "TZV-000002"; // Biryani Basmati Rice, seeded

    @BeforeAll
    void setUp() {
        taxonomyLoader.load(db);
        // E-4: a string discriminator REQUIRES a ratified vocabulary, so one is supplied here.
        registry.ratify(new Ratification(V, "wp0-test-1.0.0", List.of("pack", "processing"),
                Map.of("processing", new Ratification.Vocabulary(
                        java.util.Set.of("raw", "parboiled", "steam"),
                        Map.of("boiled", "parboiled", "sella", "parboiled")))));
    }

    @AfterAll
    void tearDown() {
        registry.clear();
    }

    @Test
    void wf1_every_ratified_discriminator_appears_in_the_key() {
        Optional<CanonicalKey> key = canonicalKeyService.derive(V, "BR-X", "single",
                Map.of("pack_size", 5, "pack_unit", "kg", "processing", "parboiled"), null);
        assertThat(key).isPresent();
        assertThat(key.get().key())
                .as("a ratified discriminator that is present MUST contribute a term")
                .contains("processing=parboiled");
    }

    @Test
    void wf1_a_missing_ratified_discriminator_suppresses_the_key() {
        Optional<CanonicalKey> key = canonicalKeyService.derive(V, "BR-X", "single",
                Map.of("pack_size", 5, "pack_unit", "kg"), null);   // processing absent
        assertThat(key)
                .as("suppress the key entirely — never silently fall back to a weaker identity")
                .isEmpty();
    }
}
