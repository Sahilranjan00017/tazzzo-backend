package com.tazzzo.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The deletion endpoint over real HTTP with real customer sessions established through the OTP-grant flow. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class AccountDeletionHttpIT extends AbstractApiIT {

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

    @Autowired OtpVerifiedGrantRepository grants;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(), Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> deletion(String token, Object body) {
        return rest.exchange(url("/v1/customer/account/deletion"), HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    @Test
    void the_full_flow_delete_then_the_token_is_dead() {
        String token = newAccessToken("+919876540001");
        assertThat(get("/v1/customer/profile", token, JsonNode.class).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> res = deletion(token, Map.of("confirm", "DELETE"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("DELETED");
        assertThat(res.getBody().get("requestId").asText()).startsWith("req_");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");

        assertThat(get("/v1/customer/profile", token, JsonNode.class).getStatusCode().value()).as("every session revoked").isEqualTo(401);
        assertThat(deletion(token, Map.of("confirm", "DELETE")).getStatusCode().value()).as("a retry after completion meets the revoked session").isEqualTo(401);
        assertThat(db.getCollection("customers").countDocuments(Filters.eq("phoneNormalized", "+919876540001"))).isZero();
    }

    @Test
    void the_explicit_confirmation_is_required() {
        String token = newAccessToken("+919876540002");
        for (Object body : new Object[]{Map.of(), Map.of("confirm", "delete"), Map.of("confirm", "YES"), Map.of("confirm", 1), Map.of("other", "DELETE")}) {
            ResponseEntity<JsonNode> res = deletion(token, body);
            assertThat(res.getStatusCode().value()).as(body.toString()).isEqualTo(400);
            assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        ResponseEntity<JsonNode> noBody = rest.exchange(url("/v1/customer/account/deletion"), HttpMethod.POST, new HttpEntity<>(headers(token)), JsonNode.class);
        assertThat(noBody.getStatusCode().value()).isEqualTo(400);
        assertThat(get("/v1/customer/profile", token, JsonNode.class).getStatusCode().value()).as("nothing happened").isEqualTo(200);
    }

    @Test
    void only_the_bearer_is_deleted_and_no_identifier_in_the_body_is_honoured() {
        String victim = newAccessToken("+919876540003");
        String other = newAccessToken("+919876540004");
        String otherId = get("/v1/customer/profile", other, JsonNode.class).getBody().get("customerId").asText();
        ResponseEntity<JsonNode> res = deletion(victim, Map.of("confirm", "DELETE", "customerId", otherId));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(get("/v1/customer/profile", other, JsonNode.class).getStatusCode().value()).as("the named customer is untouched").isEqualTo(200);
        assertThat(get("/v1/customer/profile", victim, JsonNode.class).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void unauthenticated_or_bogus_tokens_are_401_before_anything_runs() {
        assertThat(deletion(null, Map.of("confirm", "DELETE")).getStatusCode().value()).isEqualTo(401);
        assertThat(deletion("not-a-token", Map.of("confirm", "DELETE")).getStatusCode().value()).isEqualTo(401);
        assertThat(deletion("cms-test-token", Map.of("confirm", "DELETE")).getStatusCode().value()).as("a service token is not a customer").isEqualTo(401);
    }
}
