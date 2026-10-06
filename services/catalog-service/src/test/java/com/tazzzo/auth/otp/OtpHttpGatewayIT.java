package com.tazzzo.auth.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The real OTP request/verify lifecycle with {@code provider-mode=HTTP} against a loopback SMS gateway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, OtpHttpGatewayIT.TestBeans.class})
class OtpHttpGatewayIT extends AbstractApiIT {

    static final List<String> BODIES = new CopyOnWriteArrayList<>();
    static final AtomicInteger NEXT_STATUS = new AtomicInteger(202);
    static final HttpServer GATEWAY;

    static {
        try {
            GATEWAY = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GATEWAY.createContext("/send", ex -> {
            BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + "|auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(NEXT_STATUS.get(), -1);
            ex.close();
        });
        GATEWAY.start();
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.otp.hmac-key-b64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("tazzzo.customer-auth.otp.provider-mode", () -> "HTTP");
        r.add("tazzzo.customer-auth.otp.http.url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort() + "/send");
        r.add("tazzzo.customer-auth.otp.http.auth-header-value", () -> "Bearer GATEWAY-IT-SECRET");
        for (String b : List.of("request-ip", "request-phone", "verify-ip", "verify-challenge")) {
            r.add("tazzzo.customer-auth.otp." + b + ".capacity", () -> "1000");
            r.add("tazzzo.customer-auth.otp." + b + ".refill-per-second", () -> "1000");
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        RateLimitStore alwaysAllow() {
            return (buckets, cost) -> new Admission.Allowed(List.of());
        }
    }

    private ResponseEntity<JsonNode> request(String phone) {
        return rest.exchange(url("/v1/auth/otp/request"), HttpMethod.POST, new HttpEntity<>(new OtpRequestRequestDto(phone), headers(null)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> verify(String challengeId, String otp) {
        return rest.exchange(url("/v1/auth/otp/verify"), HttpMethod.POST, new HttpEntity<>(new OtpVerifyRequestDto(challengeId, otp), headers(null)), JsonNode.class);
    }

    @Test
    void the_code_reaches_the_gateway_and_verifies_end_to_end_without_leaking_to_the_customer_response() {
        NEXT_STATUS.set(202);
        BODIES.clear();
        String phone = "+919876540101";
        ResponseEntity<JsonNode> res = request(phone);
        assertThat(res.getStatusCode().value()).isEqualTo(202);
        assertThat(res.getBody().toString()).doesNotContain("9876540101").doesNotContain("otp\"");
        assertThat(BODIES).hasSize(1);
        String sent = BODIES.get(0);
        assertThat(sent).contains("\"to\":\"" + phone + "\"").contains("auth=Bearer GATEWAY-IT-SECRET");
        Matcher m = Pattern.compile("([0-9]{6}) is your Tazzzo verification code").matcher(sent);
        assertThat(m.find()).isTrue();

        ResponseEntity<JsonNode> ok = verify(res.getBody().get("challengeId").asText(), m.group(1));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("verified").asBoolean()).isTrue();
    }

    @Test
    void a_gateway_failure_is_a_503_and_leaves_no_usable_challenge() {
        BODIES.clear();
        NEXT_STATUS.set(500);
        String phone = "+919876540102";
        ResponseEntity<JsonNode> res = request(phone);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().toString()).doesNotContain("9876540102").doesNotContain("GATEWAY-IT-SECRET").doesNotContain("127.0.0.1");
        // (the test HTTP client may itself re-send a 503; every send that reached the gateway failed)
        assertThat(BODIES).isNotEmpty();
        List<String> failedCodes = new java.util.ArrayList<>();
        for (String sent : BODIES) {
            Matcher m = Pattern.compile("([0-9]{6}) is your Tazzzo verification code").matcher(sent);
            assertThat(m.find()).isTrue();
            failedCodes.add(m.group(1));
        }
        // a code a failed send carried must not verify: no challenge was activated by a failed delivery
        NEXT_STATUS.set(202);
        ResponseEntity<JsonNode> retry = request(phone);
        assertThat(retry.getStatusCode().value()).isEqualTo(202);
        for (String dead : failedCodes) {
            assertThat(verify(retry.getBody().get("challengeId").asText(), dead).getStatusCode().value()).as("the failed attempt's code is dead").isEqualTo(400);
        }
    }
}
