package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
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
 * PR-12B — {@code /v1/customer/addresses/**} exercised over real HTTP end-to-end: real
 * authentication (via the PR-11C OTP-grant -> session flow), real optimistic-concurrency headers,
 * real cross-customer ownership isolation. Mirrors {@code CustomerProfileControllerHttpIT}'s
 * conventions. The address limit is overridden to a small value so limit-reached scenarios don't
 * need 10 real inserts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressControllerHttpIT.TestBeans.class})
class AddressControllerHttpIT extends AbstractApiIT {

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
        AddressLimitProperties smallLimit() {
            AddressLimitProperties p = new AddressLimitProperties();
            p.setMaxActiveAddresses(2);
            return p;
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

    private Map<String, Object> validCreateBody(String label, String name) {
        return Map.of("label", label, "recipientName", name, "recipientPhone", "+919876500001",
                "addressLine1", "12 MG Road", "city", "Bengaluru", "state", "Karnataka", "postalCode", "560047");
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String path, String token, String ifMatch, Object body) {
        HttpHeaders h = headers(token);
        if (ifMatch != null) h.set("If-Match", ifMatch);
        return rest.exchange(url(path), HttpMethod.PATCH, new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String path, String token, String ifMatch) {
        HttpHeaders h = headers(token);
        if (ifMatch != null) h.set("If-Match", ifMatch);
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> putDefault(String addressId, String token) {
        return rest.exchange(url("/v1/customer/addresses/" + addressId + "/default"), HttpMethod.PUT,
                new HttpEntity<>(headers(token)), JsonNode.class);
    }

    // ---------- 1: unauthenticated ----------

    @Test void unauthenticated_list_is_401() {
        assertThat(get("/v1/customer/addresses", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test void unauthenticated_create_is_401() {
        assertThat(post("/v1/customer/addresses", validCreateBody("HOME", "X"), null).getStatusCode().value())
                .isEqualTo(401);
    }

    // ---------- 2/3: create/default ----------

    @Test void first_create_returns_201_default_true_version_one() {
        String token = newAccessToken("+919876511001");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", validCreateBody("HOME", "Sahil"), token);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        JsonNode body = res.getBody();
        assertThat(body.get("isDefault").asBoolean()).isTrue();
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(body.get("addressId").asText()).startsWith("ADDR_");
        assertThat(res.getHeaders().getETag()).isEqualTo("\"address-1\"");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void second_create_returns_default_false() {
        String token = newAccessToken("+919876511002");
        post("/v1/customer/addresses", validCreateBody("HOME", "A"), token);
        ResponseEntity<JsonNode> second = post("/v1/customer/addresses", validCreateBody("WORK", "B"), token);
        assertThat(second.getBody().get("isDefault").asBoolean()).isFalse();
    }

    // ---------- 4/5: list/get own only ----------

    @Test void list_returns_only_callers_addresses() {
        String tokenA = newAccessToken("+919876511003");
        String tokenB = newAccessToken("+919876511004");
        post("/v1/customer/addresses", validCreateBody("HOME", "A"), tokenA);
        post("/v1/customer/addresses", validCreateBody("HOME", "B"), tokenB);

        ResponseEntity<JsonNode> listA = get("/v1/customer/addresses", tokenA);
        assertThat(listA.getBody().get("items")).hasSize(1);
        assertThat(listA.getBody().get("items").get(0).get("recipientName").asText()).isEqualTo("A");
    }

    // ---------- 6-9: cross-customer ownership => 404, no enumeration ----------

    @Test void cross_customer_get_is_404() {
        String tokenA = newAccessToken("+919876511005");
        String tokenB = newAccessToken("+919876511006");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "A"), tokenA)
                .getBody().get("addressId").asText();

        ResponseEntity<JsonNode> res = get("/v1/customer/addresses/" + addressId, tokenB);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test void unknown_address_id_is_the_same_404_as_cross_customer() {
        String token = newAccessToken("+919876511007");
        ResponseEntity<JsonNode> res = get("/v1/customer/addresses/ADDR_doesnotexist00000001", token);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test void cross_customer_patch_is_404() {
        String tokenA = newAccessToken("+919876511008");
        String tokenB = newAccessToken("+919876511009");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "A"), tokenA)
                .getBody().get("addressId").asText();

        ResponseEntity<JsonNode> res = patch("/v1/customer/addresses/" + addressId, tokenB, "\"address-1\"",
                Map.of("recipientName", "Hijacked"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test void cross_customer_delete_is_404() {
        String tokenA = newAccessToken("+919876511010");
        String tokenB = newAccessToken("+919876511011");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "A"), tokenA)
                .getBody().get("addressId").asText();

        ResponseEntity<JsonNode> res = delete("/v1/customer/addresses/" + addressId, tokenB, "\"address-1\"");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test void cross_customer_set_default_is_404() {
        String tokenA = newAccessToken("+919876511012");
        String tokenB = newAccessToken("+919876511013");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "A"), tokenA)
                .getBody().get("addressId").asText();

        ResponseEntity<JsonNode> res = putDefault(addressId, tokenB);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    // ---------- 10-12: field validation over HTTP ----------

    @Test void invalid_postal_code_is_400() {
        String token = newAccessToken("+919876511014");
        Map<String, Object> body = new java.util.HashMap<>(validCreateBody("HOME", "X"));
        body.put("postalCode", "12345");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", body, token);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test void only_one_of_lat_lng_is_400() {
        String token = newAccessToken("+919876511015");
        Map<String, Object> body = new java.util.HashMap<>(validCreateBody("HOME", "X"));
        body.put("latitude", 12.9);
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", body, token);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test void invalid_recipient_phone_is_400() {
        String token = newAccessToken("+919876511016");
        Map<String, Object> body = new java.util.HashMap<>(validCreateBody("HOME", "X"));
        body.put("recipientPhone", "notaphone");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", body, token);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- 14: limit ----------

    @Test void address_limit_reached_is_409() {
        String token = newAccessToken("+919876511017");
        post("/v1/customer/addresses", validCreateBody("HOME", "1"), token);
        post("/v1/customer/addresses", validCreateBody("HOME", "2"), token); // limit == 2 in this test class
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", validCreateBody("HOME", "3"), token);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("code").asText()).isEqualTo("ADDRESS_LIMIT_REACHED");
    }

    // ---------- 19/20/21: PATCH concurrency headers ----------

    @Test void patch_without_if_match_is_428() {
        String token = newAccessToken("+919876511018");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token)
                .getBody().get("addressId").asText();
        ResponseEntity<JsonNode> res = patch("/v1/customer/addresses/" + addressId, token, null,
                Map.of("recipientName", "Y"));
        assertThat(res.getStatusCode().value()).isEqualTo(428);
    }

    @Test void stale_patch_is_412() {
        String token = newAccessToken("+919876511019");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token)
                .getBody().get("addressId").asText();
        ResponseEntity<JsonNode> res = patch("/v1/customer/addresses/" + addressId, token, "\"address-99\"",
                Map.of("recipientName", "Y"));
        assertThat(res.getStatusCode().value()).isEqualTo(412);
    }

    @Test void successful_patch_increments_version_and_etag() {
        String token = newAccessToken("+919876511020");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token)
                .getBody().get("addressId").asText();
        ResponseEntity<JsonNode> res = patch("/v1/customer/addresses/" + addressId, token, "\"address-1\"",
                Map.of("recipientName", "Y"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("version").asLong()).isEqualTo(2);
        assertThat(res.getHeaders().getETag()).isEqualTo("\"address-2\"");
    }

    // ---------- 22/23/24: DELETE concurrency headers ----------

    @Test void delete_without_if_match_is_428() {
        String token = newAccessToken("+919876511021");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token)
                .getBody().get("addressId").asText();
        assertThat(delete("/v1/customer/addresses/" + addressId, token, null).getStatusCode().value())
                .isEqualTo(428);
    }

    @Test void stale_delete_is_412() {
        String token = newAccessToken("+919876511022");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token)
                .getBody().get("addressId").asText();
        assertThat(delete("/v1/customer/addresses/" + addressId, token, "\"address-99\"").getStatusCode().value())
                .isEqualTo(412);
    }

    @Test void successful_delete_is_204_then_repeated_delete_is_404() {
        String token = newAccessToken("+919876511023");
        String addressId = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token)
                .getBody().get("addressId").asText();
        ResponseEntity<JsonNode> first = delete("/v1/customer/addresses/" + addressId, token, "\"address-1\"");
        assertThat(first.getStatusCode().value()).isEqualTo(204);
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");

        ResponseEntity<JsonNode> second = delete("/v1/customer/addresses/" + addressId, token, "\"address-1\"");
        assertThat(second.getStatusCode().value()).isEqualTo(404);
    }

    // ---------- set-default over HTTP ----------

    @Test void set_default_over_http() {
        String token = newAccessToken("+919876511024");
        String first = post("/v1/customer/addresses", validCreateBody("HOME", "A"), token)
                .getBody().get("addressId").asText();
        String second = post("/v1/customer/addresses", validCreateBody("WORK", "B"), token)
                .getBody().get("addressId").asText();

        ResponseEntity<JsonNode> res = putDefault(second, token);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("isDefault").asBoolean()).isTrue();

        ResponseEntity<JsonNode> firstNow = get("/v1/customer/addresses/" + first, token);
        assertThat(firstNow.getBody().get("isDefault").asBoolean()).isFalse();
    }

    // ---------- no PII / no internal identifiers leak ----------

    @Test void response_never_contains_fulfillment_or_service_area_identifiers() {
        String token = newAccessToken("+919876511025");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token);
        String raw = res.getBody().toString();
        assertThat(raw).doesNotContain("fulfillmentLocationId").doesNotContain("serviceAreaId")
                .doesNotContain("warehouseId");
    }

    @Test void response_never_contains_mongo_internal_id_style_fields() {
        String token = newAccessToken("+919876511026");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", validCreateBody("HOME", "X"), token);
        assertThat(res.getBody().has("_id")).isFalse();
        assertThat(res.getBody().has("customerId")).isFalse();
    }
}
