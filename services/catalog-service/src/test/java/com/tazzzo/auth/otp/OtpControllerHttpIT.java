package com.tazzzo.auth.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11B — {@code /v1/auth/otp/**} exercised over real HTTP (mirrors {@code HttpSurfaceBoundaryIT}
 * conventions). Proves: the surface is {@code PUBLIC_CONSUMER} (any/no Authorization header is
 * irrelevant to authority — §26), the error/cache-control contract (§5/§6/§23), and that the
 * response bodies never leak the OTP or the phone (§10/§11/§36).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, OtpControllerHttpIT.TestBeans.class})
class OtpControllerHttpIT extends AbstractApiIT {

    static final String OTP_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // AbstractApiIT.CMS_TOKEN/READ_TOKEN are package-private in com.tazzzo.catalog; these are the
    // SAME literal values it configures tazzzo.auth.cms-token/read-token to.
    static final String CMS_TOKEN = "cms-test-token";
    static final String READ_TOKEN = "read-test-token";

    @DynamicPropertySource
    static void otpProps(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.otp.hmac-key-b64", () -> OTP_KEY);
        r.add("tazzzo.customer-auth.otp.ttl-seconds", () -> "300");
        r.add("tazzzo.customer-auth.otp.resend-cooldown-seconds", () -> "30");
        r.add("tazzzo.customer-auth.otp.max-attempts", () -> "5");
        r.add("tazzzo.customer-auth.otp.grant-ttl-seconds", () -> "300");
        r.add("tazzzo.customer-auth.otp.request-ip.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.request-ip.refill-per-second", () -> "1000");
        r.add("tazzzo.customer-auth.otp.request-phone.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.request-phone.refill-per-second", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-ip.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-ip.refill-per-second", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-challenge.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-challenge.refill-per-second", () -> "1000");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        RateLimitStore alwaysAllowRateLimitStore() {
            return (buckets, cost) -> new Admission.Allowed(List.of());
        }

        @Bean
        @Primary
        OtpDeliveryProvider capturingOtpDeliveryProvider() {
            return new CapturingProvider();
        }
    }

    static class CapturingProvider implements OtpDeliveryProvider {
        static final Map<String, String> LAST_OTP_BY_PHONE = new ConcurrentHashMap<>();

        @Override
        public void sendLoginOtp(Phone phone, String otp, Duration expiresIn) {
            LAST_OTP_BY_PHONE.put(phone.value(), otp);
        }
    }

    private ResponseEntity<JsonNode> requestOtp(String phone, String token) {
        return rest.exchange(url("/v1/auth/otp/request"), HttpMethod.POST,
                new HttpEntity<>(new OtpRequestRequestDto(phone), headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> verifyOtp(String challengeId, String otp, String token) {
        return rest.exchange(url("/v1/auth/otp/verify"), HttpMethod.POST,
                new HttpEntity<>(new OtpVerifyRequestDto(challengeId, otp), headers(token)), JsonNode.class);
    }

    // ---------- surface: any/no Authorization header is irrelevant (§26) ----------

    @Test void anonymous_request_reaches_the_endpoint() {
        ResponseEntity<JsonNode> res = requestOtp("+919876500001", null);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
    }

    @Test void service_and_customer_tokens_confer_no_special_authority() {
        int anonymous = requestOtp("+919876500002", null).getStatusCode().value();
        assertThat(requestOtp("+919876500003", CMS_TOKEN).getStatusCode().value()).isEqualTo(anonymous);
        assertThat(requestOtp("+919876500004", READ_TOKEN).getStatusCode().value()).isEqualTo(anonymous);
        assertThat(requestOtp("+919876500005", "some-bogus-customer-shaped-token")
                .getStatusCode().value()).isEqualTo(anonymous);
    }

    // ---------- response shape / privacy ----------

    @Test void request_response_never_contains_the_otp_or_the_phone() {
        ResponseEntity<JsonNode> res = requestOtp("+919876500010", null);
        JsonNode body = res.getBody();
        assertThat(body.has("challengeId")).isTrue();
        assertThat(body.has("expiresInSeconds")).isTrue();
        assertThat(body.has("resendAfterSeconds")).isTrue();
        assertThat(body.has("otp")).isFalse();
        assertThat(body.has("phone")).isFalse();
        assertThat(body.toString()).doesNotContain("9876500010");
    }

    @Test void request_response_has_no_store_cache_control() {
        ResponseEntity<JsonNode> res = requestOtp("+919876500011", null);
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void full_happy_path_via_http_never_reveals_phone_and_produces_a_grant() {
        String phone = "+919876500020";
        ResponseEntity<JsonNode> reqRes = requestOtp(phone, null);
        String challengeId = reqRes.getBody().get("challengeId").asText();
        String otp = CapturingProvider.LAST_OTP_BY_PHONE.get(phone);
        assertThat(otp).matches("^[0-9]{6}$");

        ResponseEntity<JsonNode> verifyRes = verifyOtp(challengeId, otp, null);
        assertThat(verifyRes.getStatusCode().value()).isEqualTo(200);
        JsonNode body = verifyRes.getBody();
        assertThat(body.get("verified").asBoolean()).isTrue();
        assertThat(body.get("grantId").asText()).matches("^GRANT_[A-Za-z0-9_-]+$");
        assertThat(body.has("phone")).isFalse();
        assertThat(body.toString()).doesNotContain("9876500020");
        assertThat(verifyRes.getHeaders().getCacheControl()).contains("no-store");
    }

    // ---------- validation / error vocabulary ----------

    @Test void malformed_phone_is_400_invalid_request() {
        ResponseEntity<JsonNode> res = requestOtp("not-a-phone", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("OTP_INVALID_REQUEST");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void unknown_challenge_is_400_otp_invalid() {
        ResponseEntity<JsonNode> res = verifyOtp("OTP_" + "z".repeat(24), "123456", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("OTP_INVALID");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void malformed_otp_shape_is_400_invalid_request() {
        ResponseEntity<JsonNode> res = verifyOtp("OTP_" + "a".repeat(24), "abc", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("OTP_INVALID_REQUEST");
    }

    @Test void wrong_otp_on_a_real_challenge_is_400_otp_invalid() {
        String phone = "+919876500030";
        String challengeId = requestOtp(phone, null).getBody().get("challengeId").asText();
        ResponseEntity<JsonNode> res = verifyOtp(challengeId, "000000", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("OTP_INVALID");
    }
}
