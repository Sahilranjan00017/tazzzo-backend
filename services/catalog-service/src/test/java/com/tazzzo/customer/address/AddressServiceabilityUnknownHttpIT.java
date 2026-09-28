package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.serviceability.PublicServiceability;
import com.tazzzo.serviceability.ServiceabilityService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12B §25 — the serviceability dependency FAILING must yield UNKNOWN ({@code serviceable} JSON
 * {@code null}), never {@code false}: a valid, saved address must not be reported as
 * "unserviceable" merely because the answer could not currently be determined. Uses a controlled
 * failing {@link ServiceabilityService}; the real outside-coverage (=false) behavior is proven
 * separately in {@code AddressServiceabilityHttpIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressServiceabilityUnknownHttpIT.TestBeans.class})
class AddressServiceabilityUnknownHttpIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @DynamicPropertySource
    static void sessionProps(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        AddressLimitProperties bigLimit() {
            AddressLimitProperties p = new AddressLimitProperties();
            p.setMaxActiveAddresses(10);
            return p;
        }

        @Bean
        @Primary
        ServiceabilityService failingServiceability(Tx tx, MongoDatabase db, Clock clock) {
            return new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock) {
                @Override
                public PublicServiceability resolvePublic(Pincode pin) {
                    throw new RuntimeException("simulated serviceability outage pii@example.com CUS_leak");
                }
            };
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired MeterRegistry registry;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        return established.get("accessToken").asText();
    }

    private Map<String, Object> body() {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "HOME");
        m.put("recipientName", "Name");
        m.put("recipientPhone", "+919876500001");
        m.put("addressLine1", "Line1");
        m.put("city", "City");
        m.put("state", "State");
        m.put("postalCode", "560047");
        return m;
    }

    private double unknownCount() {
        var c = registry.find("address_serviceability_result").tag("result", "unknown").counter();
        return c == null ? 0 : c.count();
    }

    @Test void a_failing_serviceability_dependency_yields_json_null_not_false_and_the_address_is_saved() {
        String token = newAccessToken("+919876566001");
        ResponseEntity<JsonNode> created = post("/v1/customer/addresses", body(), token, JsonNode.class);

        assertThat(created.getStatusCode().value()).as("the address request itself still succeeds").isEqualTo(201);
        JsonNode serviceability = created.getBody().get("serviceability");
        assertThat(serviceability).as("the serviceability object exists").isNotNull();
        assertThat(serviceability.has("serviceable")).isTrue();
        assertThat(serviceability.get("serviceable").isNull()).as("UNKNOWN is JSON null").isTrue();
        assertThat(serviceability.get("serviceable").isBoolean()).as("and is NOT false").isFalse();

        String addressId = created.getBody().get("addressId").asText();
        assertThat(db.getCollection("customer_addresses").find(Filters.eq("_id", addressId)).first())
                .as("the address remains persisted").isNotNull();

        String raw = created.getBody().toString();
        assertThat(raw).doesNotContain("RuntimeException").doesNotContain("pii@example.com")
                .doesNotContain("CUS_leak").doesNotContain("simulated serviceability outage");

        ResponseEntity<JsonNode> read = get("/v1/customer/addresses/" + addressId, token, JsonNode.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody().get("serviceability").get("serviceable").isNull()).isTrue();
    }

    @Test void the_unknown_result_metric_increments() {
        String token = newAccessToken("+919876566002");
        double before = unknownCount();
        post("/v1/customer/addresses", body(), token, JsonNode.class);
        assertThat(unknownCount()).isEqualTo(before + 1);
    }
}
