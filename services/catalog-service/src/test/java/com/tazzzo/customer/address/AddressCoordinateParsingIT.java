package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.auth.otp.Phone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CVE-2026-89407 (jackson-core before 2.18.11 / 2.21.7): binding a JSON <i>string</i> to a {@code Double} runs
 * {@code NumberInput.looksLikeValidNumber}, whose float pattern backtracks quadratically. The customer address body
 * carries {@code Double latitude/longitude}, so any signed-in customer could spend about 17 s of CPU per 60 KB request
 * on jackson-core 2.17.2 (measured). With the patched parser the same body is rejected in milliseconds. Ordinary
 * coordinates keep working exactly as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class AddressCoordinateParsingIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 3));
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

    @Autowired OtpVerifiedGrantRepository grants;

    private int phoneSeq = 0;

    private String newAccessToken() {
        String phone = String.format("+91987653%04d", ++phoneSeq + (int) (System.nanoTime() % 5000));
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody()
                .get("accessToken").asText();
    }

    private static Map<String, Object> body() {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "HOME");
        m.put("recipientName", "Asha Rao");
        m.put("recipientPhone", "+919876500001");
        m.put("addressLine1", "12 MG Road");
        m.put("city", "Bengaluru");
        m.put("state", "Karnataka");
        m.put("postalCode", "560047");
        return m;
    }

    private ResponseEntity<JsonNode> create(String token, Map<String, Object> body) {
        return rest.exchange(url(PATH), HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private int listSize(String token) {
        return rest.exchange(url(PATH), HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class).getBody()
                .get("items").size();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void a_crafted_numeric_string_for_a_coordinate_is_rejected_in_bounded_time() {
        String token = newAccessToken();
        for (String field : List.of("latitude", "longitude")) {
            Map<String, Object> crafted = body();
            crafted.put(field, "1".repeat(60_000) + "x");   // about 60 KB: under the 64 KiB body limit
            long started = System.nanoTime();
            ResponseEntity<JsonNode> res = create(token, crafted);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(res.getStatusCode().value()).as(field).isEqualTo(400);
            assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(res.getBody().toString()).as("the value is never echoed").doesNotContain("1111111111");
            assertThat(millis).as(field + ": jackson-core 2.17.2 needed ~17 s for this body").isLessThan(3_000);
        }
        assertThat(listSize(token)).as("nothing was created").isZero();
    }

    @Test
    void ordinary_coordinates_are_still_accepted() {
        String token = newAccessToken();
        Map<String, Object> numbers = body();
        numbers.put("latitude", 12.9716);
        numbers.put("longitude", 77.5946);
        assertThat(create(token, numbers).getStatusCode().value()).isEqualTo(201);
        Map<String, Object> numericStrings = body();
        numericStrings.put("addressLine1", "14 MG Road");
        numericStrings.put("latitude", "12.9716");
        numericStrings.put("longitude", "77.5946");
        assertThat(create(token, numericStrings).getStatusCode().value())
                .as("numeric strings keep their existing lenient coercion").isEqualTo(201);
    }
}
