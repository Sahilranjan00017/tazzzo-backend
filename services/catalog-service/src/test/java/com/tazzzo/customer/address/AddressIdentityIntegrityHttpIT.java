package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12B — the SAME ghost-identity data-integrity scenario PR-12A closed for profile, reproduced
 * for addresses: a cryptographically valid access token backed by a genuinely active session does
 * NOT, by itself, prove the referenced customer identity still exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressIdentityIntegrityHttpIT.TestBeans.class})
class AddressIdentityIntegrityHttpIT extends AbstractApiIT {

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
    }

    @Autowired OtpVerifiedGrantRepository grants;

    private JsonNode establish(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody();
    }

    private void deleteCustomerIdentity(String customerId) {
        db.getCollection("customers").deleteOne(Filters.eq("_id", customerId));
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private Map<String, Object> validBody() {
        return Map.of("label", "HOME", "recipientName", "Name", "recipientPhone", "+919876500001",
                "addressLine1", "Line1", "city", "City", "state", "State", "postalCode", "560047");
    }

    @Test void list_with_a_valid_session_but_missing_customer_identity_is_503() {
        JsonNode established = establish("+919876533001");
        String token = established.get("accessToken").asText();
        deleteCustomerIdentity(established.get("customerId").asText());

        ResponseEntity<JsonNode> res = get("/v1/customer/addresses", token);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test void create_with_a_valid_session_but_missing_customer_identity_is_503_and_creates_no_document() {
        JsonNode established = establish("+919876533002");
        String token = established.get("accessToken").asText();
        String customerId = established.get("customerId").asText();
        deleteCustomerIdentity(customerId);

        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", validBody(), token);
        assertThat(res.getStatusCode().value()).isEqualTo(503);

        Document ghost = db.getCollection("customer_addresses").find(Filters.eq("customerId", customerId)).first();
        assertThat(ghost).as("no ghost address is ever created for a missing identity").isNull();
    }

    @Test void valid_identity_still_works_normally() {
        JsonNode established = establish("+919876533003");
        String token = established.get("accessToken").asText();
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", validBody(), token);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
    }
}
