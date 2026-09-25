package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards for the PDP path (PDP-1 §2.3, PDP-MERGE-1, TAX-REACH-1). Source-level, so the
 * forbidden shapes cannot come back quietly: a second predicate, a probe, an unbounded chain, a
 * charge after the first read, a wider projection.
 */
class ConsumerPdpGuardIT {

    private static final Path SERVICE =
            Path.of("src/main/java/com/tazzzo/catalog/consumer/ConsumerProductDetailService.java");
    private static final Path CONTROLLER =
            Path.of("src/main/java/com/tazzzo/catalog/consumer/ConsumerTaxonomyController.java");

    private static String code(Path p) throws IOException {
        return Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
    }

    @Test
    void the_predicate_is_the_shared_one_and_only_the_shared_one() throws IOException {
        String code = code(SERVICE);
        assertThat(code).contains("ConsumerEligibility.isEligible(");
        assertThat(code).doesNotContain("Filters.eq(\"lifecycle\"");
        assertThat(code).doesNotContain("\"confirmed\"");
        assertThat(code).doesNotContain("\"single\"");
        assertThat(code).doesNotContain("HOLDING_VERTICALS");
    }

    @Test
    void the_charge_precedes_the_first_product_read_and_there_is_no_probe() throws IOException {
        String code = code(SERVICE);
        int charge = code.indexOf("gate.charge(ConsumerObservability.Route.PDP, identity, 1)");
        int firstRead = code.indexOf("fetch(productId)");
        assertThat(charge).isPositive();
        assertThat(firstRead).isGreaterThan(charge);
        assertThat(code).doesNotContain(".limit(1)");
        assertThat(code).doesNotContain("hasEligibleProduct(");
        assertThat(code).doesNotContain(".skip(");
        assertThat(code).doesNotContain("countDocuments(");
    }

    @Test
    void reachability_goes_through_the_shared_seam_and_the_chain_is_bounded_with_a_visited_set() throws IOException {
        String code = code(SERVICE);
        assertThat(code).contains("scopes.isReachableVertical(");
        assertThat(code).doesNotContain("taxonomy_snapshot_nodes");
        assertThat(code).contains("MAX_MERGE_HOPS = 32");
        assertThat(code).contains("visited.add(");
        assertThat(code).doesNotContain("HttpStatus.PERMANENT_REDIRECT").doesNotContain("308").doesNotContain("Location");
    }

    @Test
    void the_product_read_is_projected_to_exactly_the_pdp_field_set() throws IOException {
        String code = code(SERVICE).replaceAll("\\s+", "");
        assertThat(code).contains("Projections.include(PDP_FIELDS)");
        assertThat(code).contains("\"_id\",\"title\",\"brand_code\",\"product_type\",\"lifecycle\","
                + "\"classification.status\",\"classification.vertical_id\",\"attributes\",\"merged_into\"");
    }

    @Test
    void the_pdp_route_lives_on_the_guarded_controller_inside_the_measured_boundary() throws IOException {
        String code = code(CONTROLLER).replaceAll("\\s+", "");
        assertThat(code).contains("@GetMapping(\"/products/{productId}\")");
        assertThat(code).contains("measured(ConsumerObservability.Route.PDP,");
        assertThat(code).contains("details.detail(productId,release,identity(request))");
    }
}
