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
 *
 * <p><b>PR-09 relocation:</b> the fetch + bounded merge walk + shared eligibility now live in the
 * single {@link com.tazzzo.catalog.consumer.ConsumerProductResolver}, reused by both the public
 * legacy PDP service and the internal commerce PDP reader. The structural guards therefore FOLLOW
 * that code to the RESOLVER, and are STRENGTHENED: neither the service nor the commerce reader may
 * re-implement (fork) the walk or the predicate. The service keeps only its charge ordering +
 * reachability seam; the controller keeps the route.
 */
class ConsumerPdpGuardIT {

    private static final Path RESOLVER =
            Path.of("src/main/java/com/tazzzo/catalog/consumer/ConsumerProductResolver.java");
    private static final Path SERVICE =
            Path.of("src/main/java/com/tazzzo/catalog/consumer/ConsumerProductDetailService.java");
    private static final Path CONTROLLER =
            Path.of("src/main/java/com/tazzzo/catalog/consumer/ConsumerTaxonomyController.java");
    private static final Path COMMERCE_READER =
            Path.of("src/main/java/com/tazzzo/commerce/read/CatalogProductDetailReader.java");

    private static String code(Path p) throws IOException {
        return Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
    }

    // ---- the resolution core lives in the resolver, and only there ----

    @Test
    void the_predicate_is_the_shared_one_and_only_the_shared_one() throws IOException {
        String code = code(RESOLVER);
        assertThat(code).contains("ConsumerEligibility.isEligible(");
        assertThat(code).doesNotContain("Filters.eq(\"lifecycle\"");
        assertThat(code).doesNotContain("\"confirmed\"");
        assertThat(code).doesNotContain("\"single\"");
        assertThat(code).doesNotContain("HOLDING_VERTICALS");
    }

    @Test
    void the_chain_is_bounded_with_a_visited_set_and_never_redirects() throws IOException {
        String code = code(RESOLVER);
        assertThat(code).contains("MAX_MERGE_HOPS = 32");
        assertThat(code).contains("visited.add(");
        assertThat(code).doesNotContain("HttpStatus.PERMANENT_REDIRECT").doesNotContain("308").doesNotContain("Location");
    }

    @Test
    void the_product_read_is_projected_narrowly_with_no_probe() throws IOException {
        String stripped = code(RESOLVER).replaceAll("\\s+", "");
        assertThat(stripped).contains("Projections.include(PDP_FIELDS)");
        // the exact ratified PDP field core is preserved (version is appended for the internal
        // freshness guard only — a single scalar, never in the public response)
        assertThat(stripped).contains("\"_id\",\"title\",\"brand_code\",\"product_type\",\"lifecycle\","
                + "\"classification.status\",\"classification.vertical_id\",\"attributes\",\"merged_into\"");
        String code = code(RESOLVER);
        assertThat(code).doesNotContain(".limit(1)");
        assertThat(code).doesNotContain("hasEligibleProduct(");
        assertThat(code).doesNotContain(".skip(");
        assertThat(code).doesNotContain("countDocuments(");
    }

    // ---- the service delegates: charge precedes resolution, reachability via the shared seam ----

    @Test
    void the_charge_precedes_the_first_product_read_and_there_is_no_probe() throws IOException {
        String code = code(SERVICE);
        int charge = code.indexOf("gate.charge(ConsumerObservability.Route.PDP, identity, 1)");
        int firstRead = code.indexOf("resolver.resolve(productId)");
        assertThat(charge).isPositive();
        assertThat(firstRead).isGreaterThan(charge);
        assertThat(code).doesNotContain(".limit(1)");
        assertThat(code).doesNotContain("hasEligibleProduct(");
        assertThat(code).doesNotContain(".skip(");
        assertThat(code).doesNotContain("countDocuments(");
    }

    @Test
    void reachability_goes_through_the_shared_seam_and_the_service_does_not_fork_the_walk() throws IOException {
        String code = code(SERVICE);
        assertThat(code).contains("scopes.isReachableVertical(");
        assertThat(code).doesNotContain("taxonomy_snapshot_nodes");
        // single-source ownership: the service must NOT re-implement the walk or the predicate
        assertThat(code).doesNotContain("MAX_MERGE_HOPS");
        assertThat(code).doesNotContain("merged_into");
        assertThat(code).doesNotContain("ConsumerEligibility.isEligible(");
        assertThat(code).doesNotContain("Projections.include");
    }

    // ---- commerce PDP reuses the SAME resolver and forks nothing ----

    @Test
    void the_commerce_reader_reuses_the_resolver_and_forks_no_resolution_semantics() throws IOException {
        String code = code(COMMERCE_READER);
        assertThat(code).contains("resolver.resolve(");
        assertThat(code).doesNotContain("MAX_MERGE_HOPS");
        assertThat(code).doesNotContain("merged_into");
        assertThat(code).doesNotContain("ConsumerEligibility.isEligible(");
        assertThat(code).doesNotContain("Filters.eq(\"lifecycle\"");
    }

    @Test
    void the_pdp_route_lives_on_the_guarded_controller_inside_the_measured_boundary() throws IOException {
        String code = code(CONTROLLER).replaceAll("\\s+", "");
        assertThat(code).contains("@GetMapping(\"/products/{productId}\")");
        assertThat(code).contains("measured(ConsumerObservability.Route.PDP,");
        assertThat(code).contains("details.detail(productId,release,identity(request))");
    }
}
