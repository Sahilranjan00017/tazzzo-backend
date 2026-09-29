package com.tazzzo.customer.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.commerce.read.CatalogCardFacts;
import com.tazzzo.commerce.read.CommerceSkuBatchReader;
import com.tazzzo.commerce.read.ProductCardBaseReader;
import com.tazzzo.commerce.read.ProductCardRuntimeEnricher;
import com.tazzzo.pricing.PricingService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-12C — persistence failure is a controlled 503 with no partial state and no leaked detail. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CartFailureInjectionHttpIT.TestBeans.class})
class CartFailureInjectionHttpIT extends AbstractApiIT {

    static final AtomicBoolean REPO_DOWN = new AtomicBoolean();
    static final AtomicBoolean COMMERCE_DEFECT = new AtomicBoolean();

    @DynamicPropertySource
    static void sessionProps(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) 1);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(k));
    }

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary
        CartRepository failingRepo(MongoDatabase database) {
            return new CartRepository(database) {
                @Override
                public Document findById(String customerId) {
                    if (REPO_DOWN.get()) throw new MongoException("secret-host.internal:27017 pii@example.com");
                    return super.findById(customerId);
                }

                @Override
                public Document findById(com.mongodb.client.ClientSession session, String customerId) {
                    if (REPO_DOWN.get()) throw new MongoException("secret-host.internal:27017 pii@example.com");
                    return super.findById(session, customerId);
                }
            };
        }

        @Bean @Primary
        CommerceSkuBatchReader visibleReader(ProductCardBaseReader bases, PricingService pricing,
                                             ProductCardRuntimeEnricher enricher) {
            return new CommerceSkuBatchReader(sku -> Optional.of(new CatalogCardFacts(sku, sku, "T", null, "V", 1L)),
                    bases, pricing, enricher) {
                @Override
                public java.util.Map<String, com.tazzzo.commerce.read.RuntimeProductCard> readCurrent(
                        java.util.Collection<String> skuIds, com.tazzzo.commerce.contract.LocationQuery location) {
                    if (COMMERCE_DEFECT.get()) {
                        // an UNEXPECTED programming defect (not a typed commerce outage)
                        throw new IllegalStateException("secret-internal-detail pii@example.com");
                    }
                    return super.readCurrent(skuIds, location);
                }
            };
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired io.micrometer.core.instrument.MeterRegistry registry;

    private double count(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private String token() {
        String phone = "+9197" + String.format("%08d", (System.nanoTime() / 7) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody()
                .get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> call(HttpMethod m, String path, String token, String ifMatch, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        if (ifMatch != null) h.set("If-Match", ifMatch);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    @Test void repository_failure_is_a_controlled_503_without_leaking_details_and_recovers() {
        String t = token();
        long cartsBefore = db.getCollection("customer_carts").countDocuments(); // shared DB: compare, don't assume empty
        REPO_DOWN.set(true);
        try {
            for (ResponseEntity<JsonNode> r : java.util.List.of(
                    call(HttpMethod.GET, "/v1/customer/cart", t, null, null),
                    call(HttpMethod.PUT, "/v1/customer/cart/items/TZP-1", t, "\"cart-0\"", Map.of("quantity", 1)),
                    call(HttpMethod.DELETE, "/v1/customer/cart/items/TZP-1", t, "\"cart-0\"", null),
                    call(HttpMethod.DELETE, "/v1/customer/cart", t, "\"cart-1\"", null))) {
                assertThat(r.getStatusCode().value()).isEqualTo(503);
                assertThat(r.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(r.getBody().toString()).doesNotContain("secret-host").doesNotContain("pii@example.com")
                        .doesNotContain("MongoException");
            }
        } finally {
            REPO_DOWN.set(false);
        }
        assertThat(call(HttpMethod.GET, "/v1/customer/cart", t, null, null).getStatusCode().value()).isEqualTo(200);
        assertThat(db.getCollection("customer_carts").countDocuments()).as("no cart created by the failed requests")
                .isEqualTo(cartsBefore);
    }

    @Test void an_unexpected_defect_is_a_safe_500_counted_exactly_once_as_internal() {
        String t = token();
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/TZP-1", t, "\"cart-0\"", Map.of("quantity", 1))
                .getStatusCode().value()).isEqualTo(200); // a line exists, so enrichment is attempted
        double internalBefore = count("customer_cart_failure", "operation", "read", "reason", "internal");
        double allBefore = count("customer_cart_failure");

        COMMERCE_DEFECT.set(true);
        ResponseEntity<JsonNode> r;
        try {
            r = call(HttpMethod.GET, "/v1/customer/cart", t, null, null);
        } finally {
            COMMERCE_DEFECT.set(false);
        }

        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody().get("code").asText()).isEqualTo("INTERNAL");
        assertThat(r.getBody().toString()).doesNotContain("secret-internal-detail").doesNotContain("pii@example.com")
                .doesNotContain("IllegalStateException");
        assertThat(count("customer_cart_failure", "operation", "read", "reason", "internal") - internalBefore)
                .as("counted exactly once as internal").isEqualTo(1);
        assertThat(count("customer_cart_failure") - allBefore).as("no other failure series bumped").isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/v1/customer/cart", t, null, null).getStatusCode().value()).isEqualTo(200);
    }
}
