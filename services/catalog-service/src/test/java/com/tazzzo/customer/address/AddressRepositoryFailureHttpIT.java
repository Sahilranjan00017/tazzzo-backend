package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12B §23 — a persistence-dependency outage in the address store must map to a controlled
 * {@code 503 SERVICE_UNAVAILABLE}, never a raw {@code 500} and never a fake {@code 401}, and the
 * response body must never leak the underlying exception class/message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressRepositoryFailureHttpIT.TestBeans.class})
class AddressRepositoryFailureHttpIT extends AbstractApiIT {

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
        AddressRepository alwaysFailingRepository(MongoDatabase db) {
            return new AddressRepository(db) {
                @Override public List<Document> findAllByCustomer(String customerId) {
                    throw new RuntimeException("simulated address store outage pii@example.com");
                }
            };
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        return established.get("accessToken").asText();
    }

    @Test void list_maps_a_persistence_outage_to_503_without_leaking_details() {
        String token = newAccessToken("+919876544002");
        ResponseEntity<JsonNode> res = get("/v1/customer/addresses", token, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().toString()).doesNotContain("RuntimeException")
                .doesNotContain("pii@example.com");
    }
}
