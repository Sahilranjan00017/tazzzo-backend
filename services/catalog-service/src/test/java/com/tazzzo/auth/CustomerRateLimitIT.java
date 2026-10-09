package com.tazzzo.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.session.CustomerSessionRepository;
import com.tazzzo.catalog.CatalogApplication;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-customer read/write admission on {@code /v1/customer/**} against a real Redis: separate buckets per customer and
 * per kind, 429 + Retry-After in the customer envelope, and an unauthenticated request is a 401 that costs nothing.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CustomerRateLimitIT.Probe.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class CustomerRateLimitIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        MONGO.start();
        REDIS.start();
    }

    static final int READS = 3;
    static final int WRITES = 2;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_customer_rl_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "1");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "1");
        r.add("tazzzo.customer-rate-limit.reads.capacity", () -> String.valueOf(READS));
        r.add("tazzzo.customer-rate-limit.reads.refill-per-second", () -> "0.0001");
        r.add("tazzzo.customer-rate-limit.writes.capacity", () -> String.valueOf(WRITES));
        r.add("tazzzo.customer-rate-limit.writes.refill-per-second", () -> "0.0001");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(
                "customer-auth-fixture-key-32byte!".getBytes(StandardCharsets.UTF_8)));
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(
                "customer-session-fixture-key-32b".getBytes(StandardCharsets.UTF_8)));
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired CustomerAccessTokenCodec codec;
    @Autowired CustomerSessionRepository sessions;

    /** HttpClient 5 retries a 429 and honours Retry-After (hours here): disable it so a refusal is observed, not slept on. */
    @BeforeAll
    void seed() {
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(
                org.apache.hc.client5.http.impl.classic.HttpClients.custom().disableAutomaticRetries()
                        .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                                .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(20)).build())
                        .build()));
        for (String n : new String[]{"rlread01", "rlwrite1", "rlothr01", "rlunau01"}) {
            sessions.create(new SessionId("SES_" + n), new CustomerId("CUS_" + n), Instant.now(), Instant.now().plusSeconds(3600),
                    "fixture-digest".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Test-only fixture, never a production endpoint. */
    @RestController
    static class Probe {
        @GetMapping("/v1/customer/_rlprobe")
        public Map<String, String> read(HttpServletRequest request) {
            return Map.of("customerId", CustomerPrincipalResolver.require(request).customerId().value());
        }

        @PostMapping("/v1/customer/_rlprobe")
        public Map<String, String> write(HttpServletRequest request) {
            return Map.of("customerId", CustomerPrincipalResolver.require(request).customerId().value());
        }
    }

    String bearer(String n) {
        return "Bearer " + codec.issue(new CustomerPrincipal(new CustomerId("CUS_" + n), new SessionId("SES_" + n)),
                Duration.ofMinutes(15));
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String auth) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (auth != null) {
            h.set("Authorization", auth);
        }
        return rest.exchange("http://localhost:" + port + "/v1/customer/_rlprobe", method,
                new HttpEntity<>(method == HttpMethod.POST ? "{}" : null, h), JsonNode.class);
    }

    @Test
    void reads_are_budgeted_per_customer_and_a_refusal_is_429_with_retry_after() {
        String me = bearer("rlread01");
        for (int i = 0; i < READS; i++) {
            assertThat(call(HttpMethod.GET, me).getStatusCode().value()).as("read %d", i).isEqualTo(200);
        }
        ResponseEntity<JsonNode> refused = call(HttpMethod.GET, me);
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(refused.getHeaders().getFirst("Retry-After")).matches("[1-9][0-9]*");
        assertThat(refused.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(refused.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(refused.getBody().get("requestId").asText()).startsWith("req_");
        assertThat(refused.getBody().toString()).doesNotContain("CUS_").doesNotContain("SES_");

        // writes are a separate bucket: exhausted reads never block checkout
        assertThat(call(HttpMethod.POST, me).getStatusCode().value()).isEqualTo(200);
        // and another customer is untouched
        assertThat(call(HttpMethod.GET, bearer("rlothr01")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void writes_have_their_own_smaller_budget() {
        String me = bearer("rlwrite1");
        for (int i = 0; i < WRITES; i++) {
            assertThat(call(HttpMethod.POST, me).getStatusCode().value()).as("write %d", i).isEqualTo(200);
        }
        assertThat(call(HttpMethod.POST, me).getStatusCode().value()).isEqualTo(429);
        assertThat(call(HttpMethod.GET, me).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void an_unauthenticated_request_is_401_and_spends_nothing() {
        for (int i = 0; i < READS + 3; i++) {
            ResponseEntity<JsonNode> r = call(HttpMethod.GET, i % 2 == 0 ? null : "Bearer not-a-token");
            assertThat(r.getStatusCode().value()).isEqualTo(401);
        }
        String me = bearer("rlunau01");
        for (int i = 0; i < READS; i++) {
            assertThat(call(HttpMethod.GET, me).getStatusCode().value()).isEqualTo(200);
        }
    }
}
