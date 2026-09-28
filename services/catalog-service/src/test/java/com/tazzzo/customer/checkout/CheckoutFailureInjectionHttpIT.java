package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.read.CatalogCardFacts;
import com.tazzzo.commerce.read.CommerceReadUnavailableException;
import com.tazzzo.commerce.read.CommerceSkuBatchReader;
import com.tazzzo.commerce.read.ProductCardBaseReader;
import com.tazzzo.commerce.read.ProductCardRuntimeEnricher;
import com.tazzzo.commerce.read.RuntimeProductCard;
import com.tazzzo.pricing.PricingService;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-13A — a commerce dependency OUTAGE is a controlled 503 (never disguised as an unserviceable
 * address or an item rejection), and an UNEXPECTED defect is a safe 500 counted exactly once as
 * {@code internal}. No quote is ever persisted in either case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CheckoutFailureInjectionHttpIT.TestBeans.class})
class CheckoutFailureInjectionHttpIT extends AbstractApiIT {

    enum Mode { NORMAL, OUTAGE, DEFECT }

    static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.NORMAL);
    /** Server-side count of quote POSTs: the HTTP client retries 503s, so one call may arrive twice. */
    static final java.util.concurrent.atomic.AtomicInteger QUOTE_POSTS = new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) 1);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(k));
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> quotePostCounter() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> reg =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>((req, res, chain) -> {
                        if (req instanceof jakarta.servlet.http.HttpServletRequest h && "POST".equals(h.getMethod())
                                && h.getRequestURI().endsWith("/checkout/quote")) {
                            QUOTE_POSTS.incrementAndGet();
                        }
                        chain.doFilter(req, res);
                    });
            reg.setOrder(Integer.MIN_VALUE);
            return reg;
        }

        @Bean @Primary
        CommerceSkuBatchReader faultyReader(ProductCardBaseReader bases, PricingService pricing,
                                            ProductCardRuntimeEnricher enricher) {
            return new CommerceSkuBatchReader(sku -> Optional.of(new CatalogCardFacts(sku, sku, "T", null, "V", 1L)),
                    bases, pricing, enricher) {
                @Override
                public Map<String, RuntimeProductCard> readCurrent(Collection<String> skuIds, LocationQuery location) {
                    switch (MODE.get()) {
                        case OUTAGE -> throw new CommerceReadUnavailableException(
                                CommerceReadUnavailableException.Category.SERVICEABILITY,
                                "serviceability at fulfillment FUL-SECRET-1 unavailable");
                        case DEFECT -> throw new IllegalStateException("secret-internal-detail pii@example.com");
                        default -> { }
                    }
                    return super.readCurrent(skuIds, location);
                }
            };
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired MeterRegistry registry;

    private String token() {
        String phone = "+9196" + String.format("%08d", (System.nanoTime() / 7) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody()
                .get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> call(HttpMethod m, String path, String token, HttpHeaders extra, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        if (extra != null) h.putAll(extra);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private double count(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private ResponseEntity<JsonNode> quote(String t, String addressId) {
        HttpHeaders h = new HttpHeaders();
        h.set("If-Match", "\"cart-1\"");
        h.set("Idempotency-Key", "idem-" + java.util.UUID.randomUUID());
        return call(HttpMethod.POST, "/v1/customer/checkout/quote", t, h, Map.of("addressId", addressId));
    }

    private String[] customerWithCartAndAddress() {
        String t = token();
        HttpHeaders h = new HttpHeaders();
        h.set("If-Match", "\"cart-0\"");
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/TZP-1", t, h, Map.of("quantity", 1))
                .getStatusCode().value()).isEqualTo(200);
        String addr = post("/v1/customer/addresses", Map.of("label", "HOME", "recipientName", "N",
                "recipientPhone", "+919876500001", "addressLine1", "L1", "city", "C", "state", "S",
                "postalCode", "560201"), t, JsonNode.class).getBody().get("addressId").asText();
        return new String[]{t, addr};
    }

    @Test void a_commerce_outage_is_a_controlled_503_not_an_unserviceable_or_item_rejection() {
        String[] c = customerWithCartAndAddress();
        long quotesBefore = db.getCollection("checkout_quotes").countDocuments();
        double before = count("customer_checkout_failure", "operation", "create_quote", "reason", "unavailable");
        double allBefore = count("customer_checkout_failure");
        int postsBefore = QUOTE_POSTS.get();
        MODE.set(Mode.OUTAGE);
        ResponseEntity<JsonNode> res;
        try {
            res = quote(c[0], c[1]);
        } finally {
            MODE.set(Mode.NORMAL);
        }
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().toString()).doesNotContain("FUL-SECRET").doesNotContain("serviceability");
        int posts = QUOTE_POSTS.get() - postsBefore;
        assertThat(posts).isGreaterThanOrEqualTo(1);
        assertThat(count("customer_checkout_failure", "operation", "create_quote", "reason", "unavailable") - before)
                .as("exactly one failure per request the server received").isEqualTo(posts);
        assertThat(count("customer_checkout_failure") - allBefore).as("no other failure series bumped").isEqualTo(posts);
        assertThat(db.getCollection("checkout_quotes").countDocuments()).as("no quote persisted").isEqualTo(quotesBefore);
    }

    @Test void an_unexpected_defect_is_a_safe_500_counted_exactly_once_as_internal() {
        String[] c = customerWithCartAndAddress();
        long quotesBefore = db.getCollection("checkout_quotes").countDocuments();
        double before = count("customer_checkout_failure", "operation", "create_quote", "reason", "internal");
        double allBefore = count("customer_checkout_failure");
        MODE.set(Mode.DEFECT);
        ResponseEntity<JsonNode> res;
        try {
            res = quote(c[0], c[1]);
        } finally {
            MODE.set(Mode.NORMAL);
        }
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL");
        assertThat(res.getBody().toString()).doesNotContain("secret-internal-detail").doesNotContain("pii@example.com")
                .doesNotContain("IllegalStateException");
        assertThat(count("customer_checkout_failure", "operation", "create_quote", "reason", "internal") - before)
                .as("counted exactly once as internal").isEqualTo(1);
        assertThat(count("customer_checkout_failure") - allBefore).as("no other failure series bumped").isEqualTo(1);
        assertThat(db.getCollection("checkout_quotes").countDocuments()).as("no quote persisted").isEqualTo(quotesBefore);
    }
}
