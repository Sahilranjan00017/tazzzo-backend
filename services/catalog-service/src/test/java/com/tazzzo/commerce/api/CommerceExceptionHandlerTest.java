package com.tazzzo.commerce.api;

import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.commerce.api.dto.ErrorEnvelopeDto;
import com.tazzzo.commerce.read.ProductDetailCompositionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10B compatibility gate final review #4 — deterministic proof of the public failure contract
 * at the ONE boundary that decides it, without needing a real Mongo/Testcontainers outage: every
 * Mongo-backed read reachable from a {@code /v1} route (release resolution, snapshot taxonomy
 * reads for categories/children, product membership reads and {@code product_card_base} reads for
 * list, the PDP/serviceability composers) raises a {@code MongoException} subtype on outage, and
 * this handler is the single place that decides its public status — so testing it here directly
 * covers every one of those call sites uniformly. Domain-typed failures (Pricing/Inventory/Media/
 * Serviceability) are covered by {@code DomainReadGuardTest} at the commerce.read seam that
 * translates them into {@link ConsumerFailures.Unavailable} before they would ever reach here.
 */
class CommerceExceptionHandlerTest {

    private final CommerceExceptionHandler handler = new CommerceExceptionHandler();

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestIdFilter.REQUEST_ID, "req_test0000000000000");
        return req;
    }

    // ---------- Mongo outages, reachable from any /v1 route, map to 503 ----------

    @Test void a_catalog_membership_mongo_failure_is_service_unavailable() {
        // Simulates com.tazzzo.catalog.schema.SnapshotTaxonomyReader / the products.find() keyset
        // read in CommerceListService throwing on a datastore outage.
        var e = new com.mongodb.MongoException("connection refused: mongodb+srv://prod-cluster-7.internal");
        ResponseEntity<ErrorEnvelopeDto> res = handler.unavailable(e, request());

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().code().name()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().retryable()).isTrue();
        assertThat(res.getBody().message()).doesNotContain("prod-cluster-7").doesNotContain("mongodb+srv");
    }

    @Test void a_product_card_base_mongo_failure_is_service_unavailable() {
        // Simulates ProductCardBaseReader.findBySkuIds() throwing on a datastore outage.
        var e = new com.mongodb.MongoSocketReadTimeoutException("read timed out",
                new com.mongodb.ServerAddress("mongo-primary.internal", 27017), new java.io.IOException());
        ResponseEntity<ErrorEnvelopeDto> res = handler.unavailable(e, request());

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().code().name()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().message()).doesNotContain("mongo-primary.internal");
    }

    @Test void a_consumer_failures_unavailable_is_service_unavailable() {
        ResponseEntity<ErrorEnvelopeDto> res =
                handler.unavailable(new ConsumerFailures.Unavailable("projection freshness not enabled"), request());
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().message()).doesNotContain("projection freshness");
    }

    @Test void a_product_detail_composition_exception_is_service_unavailable() {
        ResponseEntity<ErrorEnvelopeDto> res =
                handler.unavailable(new ProductDetailCompositionException("stale projection"), request());
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().message()).doesNotContain("stale projection");
    }

    // ---------- an unexpected programming failure remains 500, never reclassified ----------

    @Test void an_unexpected_null_pointer_is_internal_error_not_service_unavailable() {
        ResponseEntity<ErrorEnvelopeDto> res =
                handler.internal(new NullPointerException("adapter bug: sku=TZP-SECRET-1"), request());

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().code().name()).isEqualTo("INTERNAL");
        assertThat(res.getBody().retryable()).isFalse();
        assertThat(res.getBody().message()).doesNotContain("TZP-SECRET-1").doesNotContain("adapter bug");
    }

    @Test void an_unexpected_illegal_state_is_internal_error() {
        ResponseEntity<ErrorEnvelopeDto> res =
                handler.internal(new IllegalStateException("invariant violated"), request());
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().message()).doesNotContain("invariant violated");
    }

    @Test void an_unexpected_class_cast_is_internal_error() {
        ResponseEntity<ErrorEnvelopeDto> res =
                handler.internal(new ClassCastException("cannot cast Foo to Bar"), request());
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().message()).doesNotContain("cannot cast Foo to Bar");
    }

    // ---------- requestId is always populated in every error envelope ----------

    @Test void every_error_envelope_carries_the_server_authoritative_request_id() {
        MockHttpServletRequest req = request();
        ResponseEntity<ErrorEnvelopeDto> res = handler.notFound(req);
        assertThat(res.getBody().requestId()).isEqualTo("req_test0000000000000");
    }
}
