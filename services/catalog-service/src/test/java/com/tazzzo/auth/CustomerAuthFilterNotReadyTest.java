package com.tazzzo.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening — {@code CustomerAuthFilter} must distinguish a SERVER misconfiguration (503) from a
 * bad credential (401). Exercised directly against the filter (no full Spring context needed) so a
 * NOT_READY codec / missing {@code SessionAuthority} can be constructed deterministically.
 */
class CustomerAuthFilterNotReadyTest {

    private static final CustomerAuthObservability NOOP_OBSERVABILITY =
            new CustomerAuthObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    private static CustomerAccessTokenCodec notReadyCodec() {
        return new CustomerAccessTokenCodec(new CustomerAuthProperties(), Clock.systemUTC());
    }

    private static CustomerAccessTokenCodec readyCodec(String keyB64) {
        CustomerAuthProperties props = new CustomerAuthProperties();
        props.setAccessTokenHmacKeyB64(keyB64);
        return new CustomerAccessTokenCodec(props, Clock.systemUTC());
    }

    private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    private static MockHttpServletRequest customerRequest(String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/customer/_probe");
        if (bearer != null) {
            req.addHeader("Authorization", bearer);
        }
        return req;
    }

    @Test void customer_route_with_not_ready_codec_is_503_not_401() throws Exception {
        CustomerAuthFilter filter = new CustomerAuthFilter(notReadyCodec(), new FixedObjectProvider<>(
                (customerId, sessionId) -> true), NOOP_OBSERVABILITY);
        MockHttpServletRequest req = customerRequest("Bearer whatever-token-value");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("SERVICE_UNAVAILABLE");
        assertThat(res.getHeader("Cache-Control")).contains("no-store");
        assertThat(res.getHeader("WWW-Authenticate"))
                .as("503 is not a credential challenge").isNull();
    }

    @Test void customer_route_with_missing_session_authority_is_503_not_401() throws Exception {
        CustomerAccessTokenCodec codec = readyCodec(KEY);
        String token = codec.issue(new CustomerPrincipal(new CustomerId("CUS_readytest01"),
                new SessionId("SES_readytest01")), Duration.ofMinutes(15));
        CustomerAuthFilter filter = new CustomerAuthFilter(codec, new FixedObjectProvider<>(null), NOOP_OBSERVABILITY);

        MockHttpServletRequest req = customerRequest("Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("SERVICE_UNAVAILABLE");
    }

    @Test void customer_route_with_failing_session_authority_is_503_not_401() throws Exception {
        CustomerAccessTokenCodec codec = readyCodec(KEY);
        String token = codec.issue(new CustomerPrincipal(new CustomerId("CUS_readytest02"),
                new SessionId("SES_readytest02")), Duration.ofMinutes(15));
        SessionAuthority throwing = (customerId, sessionId) -> {
            throw new RuntimeException("simulated backing-dependency failure");
        };
        CustomerAuthFilter filter = new CustomerAuthFilter(codec, new FixedObjectProvider<>(throwing), NOOP_OBSERVABILITY);

        MockHttpServletRequest req = customerRequest("Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus())
                .as("the authority's OWN dependency failing is still a readiness problem, not a bad credential")
                .isEqualTo(503);
    }

    @Test void ordinary_missing_credential_is_still_401_not_503() throws Exception {
        CustomerAuthFilter filter = new CustomerAuthFilter(readyCodec(KEY), new FixedObjectProvider<>(
                (customerId, sessionId) -> true), NOOP_OBSERVABILITY);
        MockHttpServletRequest req = customerRequest(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(res.getContentAsString()).contains("UNAUTHENTICATED");
    }

    @Test void ordinary_bad_signature_is_still_401_not_503() throws Exception {
        CustomerAuthFilter filter = new CustomerAuthFilter(readyCodec(KEY), new FixedObjectProvider<>(
                (customerId, sessionId) -> true), NOOP_OBSERVABILITY);
        MockHttpServletRequest req = customerRequest("Bearer definitely-not-a-real-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test void revoked_session_is_still_401_not_503() throws Exception {
        CustomerAccessTokenCodec codec = readyCodec(KEY);
        String token = codec.issue(new CustomerPrincipal(new CustomerId("CUS_revokedtest1"),
                new SessionId("SES_revokedtest1")), Duration.ofMinutes(15));
        CustomerAuthFilter filter = new CustomerAuthFilter(codec, new FixedObjectProvider<>(
                (customerId, sessionId) -> false), NOOP_OBSERVABILITY); // authority is READY, just says "not active"

        MockHttpServletRequest req = customerRequest("Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
    }
}
