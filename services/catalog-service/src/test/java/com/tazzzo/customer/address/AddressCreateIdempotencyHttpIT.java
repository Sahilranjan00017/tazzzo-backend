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
import org.bson.Document;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Address create with an optional Idempotency-Key over real HTTP and Mongo: replay returns the same address and does not
 * count twice, the same key with another body conflicts, keys are per customer, a deleted address spends its key,
 * concurrent retries produce exactly one durable address, the key is never stored, and rows expire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressCreateIdempotencyHttpIT.TestBeans.class})
@Timeout(120)
class AddressCreateIdempotencyHttpIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));
    static final String PATH = "/v1/customer/addresses";

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

    private int phoneSeq = 0;

    private String newAccessToken() {
        String phone = String.format("+91987652%04d", ++phoneSeq + (int) (System.nanoTime() % 5000));
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody()
                .get("accessToken").asText();
    }

    private static Map<String, Object> body(String line1) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "HOME");
        m.put("recipientName", "Asha Rao");
        m.put("recipientPhone", "+919876500001");
        m.put("addressLine1", line1);
        m.put("city", "Bengaluru");
        m.put("state", "Karnataka");
        m.put("postalCode", "560047");
        return m;
    }

    private ResponseEntity<JsonNode> create(String token, String key, Object body) {
        HttpHeaders h = headers(token);
        if (key != null) h.set("Idempotency-Key", key);
        return rest.exchange(url(PATH), HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    private int listSize(String token) {
        return rest.exchange(url(PATH), HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class).getBody()
                .get("items").size();
    }

    private String customerOf(String addressId) {
        return db.getCollection("customer_addresses").find(new Document("_id", addressId)).first().getString("customerId");
    }

    @Test
    void a_replay_returns_the_same_address_and_never_counts_twice_against_the_limit() {
        String token = newAccessToken();
        ResponseEntity<JsonNode> first = create(token, "retry-key-0001", body("12 MG Road"));
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String id = first.getBody().get("addressId").asText();
        for (int i = 0; i < 3; i++) {
            ResponseEntity<JsonNode> again = create(token, "retry-key-0001", body("12 MG Road"));
            assertThat(again.getStatusCode().value()).isEqualTo(201);
            assertThat(again.getBody().get("addressId").asText()).isEqualTo(id);
            assertThat(again.getBody().get("version").asLong()).isEqualTo(1);
        }
        // the normalised request is what counts: surrounding whitespace is the same request
        assertThat(create(token, "retry-key-0001", body("  12 MG Road ")).getBody().get("addressId").asText()).isEqualTo(id);
        assertThat(listSize(token)).isEqualTo(1);
        // limit is 2: the replays did not consume it, so one more distinct create still fits, a third does not
        assertThat(create(token, null, body("1 Brigade Road")).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<JsonNode> full = create(token, "retry-key-0002", body("9 Church Street"));
        assertThat(full.getStatusCode().value()).isEqualTo(409);
        assertThat(full.getBody().get("code").asText()).isEqualTo("ADDRESS_LIMIT_REACHED");
        // ...and a replay of the first still answers even at the limit
        assertThat(create(token, "retry-key-0001", body("12 MG Road")).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void the_same_key_with_another_request_conflicts_and_writes_nothing() {
        String token = newAccessToken();
        create(token, "retry-key-0003", body("12 MG Road"));
        ResponseEntity<JsonNode> other = create(token, "retry-key-0003", body("77 Other Road"));
        assertThat(other.getStatusCode().value()).isEqualTo(409);
        assertThat(other.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(other.getBody().toString()).doesNotContain("retry-key");
        assertThat(listSize(token)).isEqualTo(1);
    }

    @Test
    void keys_are_scoped_per_customer() {
        String a = newAccessToken(), b = newAccessToken();
        String idA = create(a, "shared-key-01", body("12 MG Road")).getBody().get("addressId").asText();
        String idB = create(b, "shared-key-01", body("12 MG Road")).getBody().get("addressId").asText();
        assertThat(idA).isNotEqualTo(idB);
        assertThat(customerOf(idA)).isNotEqualTo(customerOf(idB));
        // and a different body under the same key for another customer is not a conflict
        assertThat(create(b, "shared-key-02", body("x")).getStatusCode().value()).isEqualTo(201);
        assertThat(create(a, "shared-key-02", body("y")).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void a_replay_after_an_edit_returns_the_current_state_and_after_a_delete_conflicts() {
        String token = newAccessToken();
        String id = create(token, "retry-key-0004", body("12 MG Road")).getBody().get("addressId").asText();
        HttpHeaders h = headers(token);
        h.set("If-Match", "\"address-1\"");
        assertThat(rest.exchange(url(PATH + "/" + id), HttpMethod.PATCH, new HttpEntity<>(Map.of("landmark", "Near Park"), h),
                JsonNode.class).getStatusCode().value()).isEqualTo(200);
        JsonNode replay = create(token, "retry-key-0004", body("12 MG Road")).getBody();
        assertThat(replay.get("addressId").asText()).isEqualTo(id);
        assertThat(replay.get("version").asLong()).isEqualTo(2);

        HttpHeaders d = headers(token);
        d.set("If-Match", "\"address-2\"");
        assertThat(rest.exchange(url(PATH + "/" + id), HttpMethod.DELETE, new HttpEntity<>(d), JsonNode.class)
                .getStatusCode().value()).isEqualTo(204);
        ResponseEntity<JsonNode> spent = create(token, "retry-key-0004", body("12 MG Road"));
        assertThat(spent.getStatusCode().value()).isEqualTo(409);
        assertThat(spent.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(listSize(token)).isZero();
    }

    @Test
    void without_a_key_create_is_unchanged_and_a_malformed_key_is_400() {
        String token = newAccessToken();
        assertThat(create(token, null, body("12 MG Road")).getStatusCode().value()).isEqualTo(201);
        assertThat(create(token, null, body("12 MG Road")).getStatusCode().value()).isEqualTo(201);
        assertThat(listSize(token)).isEqualTo(2);
        String other = newAccessToken();
        for (String bad : new String[]{"short", "has space in it", "x".repeat(65), "bad!chars#1"}) {
            assertThat(create(other, bad, body("12 MG Road")).getStatusCode().value()).as(bad).isEqualTo(400);
        }
        assertThat(listSize(other)).isZero();
    }

    @Test
    void the_key_is_never_stored_and_rows_carry_a_bounded_expiry() {
        String token = newAccessToken();
        String id = create(token, "secret-key-abc123", body("12 MG Road")).getBody().get("addressId").asText();
        Document row = db.getCollection("customer_address_idempotency").find(new Document("address_id", id)).first();
        assertThat(row).isNotNull();
        assertThat(row.toJson()).doesNotContain("secret-key-abc123").doesNotContain("MG Road").doesNotContain("+91");
        assertThat(row.keySet()).containsExactlyInAnyOrder("_id", "customer_id", "request_hash", "address_id", "created_at", "expire_at");
        assertThat(row.getString("_id")).isEqualTo(customerOf(id) + "|" + AddressIdempotencyRepository.sha256Hex("secret-key-abc123"));
        assertThat(java.time.Duration.between(row.getDate("created_at").toInstant(), row.getDate("expire_at").toInstant()))
                .isEqualTo(java.time.Duration.ofHours(24));
    }

    @Test
    void an_expired_key_row_no_longer_replays() {
        String token = newAccessToken();
        String id = create(token, "retry-key-0005", body("12 MG Road")).getBody().get("addressId").asText();
        db.getCollection("customer_address_idempotency").updateOne(new Document("address_id", id),
                new Document("$set", new Document("expire_at", java.util.Date.from(Instant.now().minusSeconds(1)))));
        String second = create(token, "retry-key-0005", body("12 MG Road")).getBody().get("addressId").asText();
        assertThat(second).isNotEqualTo(id);
        assertThat(listSize(token)).isEqualTo(2);
    }

    @Test
    void concurrent_retries_with_one_key_create_exactly_one_address() throws Exception {
        String token = newAccessToken();
        int n = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return create(token, "race-key-00001", body("12 MG Road"));
                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<ResponseEntity<JsonNode>> f : calls) {
                ResponseEntity<JsonNode> r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
                ids.add(r.getBody().get("addressId").asText());
            }
            assertThat(ids).hasSize(1);
            assertThat(listSize(token)).isEqualTo(1);
            String customer = customerOf(ids.iterator().next());
            assertThat(db.getCollection("customer_addresses").countDocuments(new Document("customerId", customer))).isEqualTo(1);
            Document stateDoc = db.getCollection("customer_address_state").find(new Document("_id", customer)).first();
            assertThat(stateDoc.get("addressCount", Number.class).longValue()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
