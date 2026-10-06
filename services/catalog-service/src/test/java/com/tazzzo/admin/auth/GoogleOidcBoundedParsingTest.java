package com.tazzzo.admin.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * CVE-2025-53864 (nimbus-jose-jwt before 9.37.4 / 10.0.2): a deeply nested JSON object in a JWT drove the JSON parser
 * into uncontrolled recursion. The admin bearer path parses an attacker-supplied token BEFORE any signature check
 * (header, then claims for key selection), so the bound must hold for unauthenticated input. A {@link StackOverflowError}
 * is an {@code Error}, not an exception: it would escape {@link GoogleOidcVerifier}'s exception mapping entirely.
 *
 * <p>Every case must end as a plain {@link AdminAuthRejection#INVALID_TOKEN}, quickly, with no Error escaping and
 * nothing of the token reflected anywhere.
 */
class GoogleOidcBoundedParsingTest {

    static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** Far beyond any legitimate claim shape; deep enough to exhaust a default thread stack with an unbounded parser. */
    static final int DEEP = 100_000;

    final GoogleIdTokens tokens = new GoogleIdTokens("kid-1");

    GoogleOidcAuthenticator authenticator() {
        HumanAdminSettings s = HumanAdminSettings.from(GoogleIdTokens.properties());
        return new GoogleOidcAuthenticator(new GoogleOidcVerifier(GoogleIdTokens.settings(), tokens.trustedKeys(), CLOCK),
                s.allowlist(), GoogleIdTokens.settings().credentialId());
    }

    AdminAuthentication authenticate(String token) {
        return authenticator().authenticate(
                AdminBearerCredential.fromAuthorizationHeader("Bearer " + token).orElseThrow());
    }

    static String nestedArray(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Valid claims for the allowlisted writer, plus one extra claim nested {@code depth} levels deep. */
    static String claimsWithNestedExtra(int depth) {
        long iat = NOW.getEpochSecond();
        return "{\"iss\":\"https://accounts.google.com\",\"azp\":\"" + GoogleIdTokens.AUDIENCE + "\",\"aud\":\""
                + GoogleIdTokens.AUDIENCE + "\",\"sub\":\"" + GoogleIdTokens.WRITER + "\",\"hd\":\"" + GoogleIdTokens.DOMAIN
                + "\",\"email_verified\":true,\"iat\":" + iat + ",\"exp\":" + (iat + 3600)
                + ",\"x\":" + nestedArray(depth) + "}";
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void a_validly_signed_token_with_a_deeply_nested_claim_is_an_invalid_token_not_an_error() {
        String token = tokens.signRaw(claimsWithNestedExtra(DEEP));
        assertThatCode(() -> authenticate(token)).doesNotThrowAnyException();
        assertThat(authenticate(token)).isEqualTo(new AdminAuthentication.Rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void a_forged_token_with_a_deeply_nested_payload_is_rejected_before_any_signature_work() {
        String header = b64("{\"alg\":\"RS256\",\"kid\":\"kid-1\"}");
        String token = header + "." + b64(claimsWithNestedExtra(DEEP)) + "." + b64("not-a-signature");
        assertThatCode(() -> authenticate(token)).doesNotThrowAnyException();
        assertThat(authenticate(token)).isEqualTo(new AdminAuthentication.Rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void a_deeply_nested_header_is_rejected() {
        String header = b64("{\"alg\":\"RS256\",\"kid\":\"kid-1\",\"x\":" + nestedArray(DEEP) + "}");
        String token = header + "." + b64(claimsWithNestedExtra(1)) + "." + b64("not-a-signature");
        assertThatCode(() -> authenticate(token)).doesNotThrowAnyException();
        assertThat(authenticate(token)).isEqualTo(new AdminAuthentication.Rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void the_largest_nesting_that_fits_an_http_header_is_rejected_too() {
        // server.max-http-request-header-size is 16 KB: the depth an attacker can actually deliver over HTTP.
        int depth = 5_500;
        String token = tokens.signRaw(claimsWithNestedExtra(depth));
        assertThat(("Bearer " + token).length()).isLessThan(16 * 1024);
        assertThatCode(() -> authenticate(token)).doesNotThrowAnyException();
        assertThat(authenticate(token)).isEqualTo(new AdminAuthentication.Rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void a_realistic_google_token_with_ordinary_nesting_still_authenticates() {
        // bounded parsing must not change legitimate behaviour: a claim nested a few levels deep is fine
        String token = tokens.signRaw(claimsWithNestedExtra(3));
        assertThat(authenticate(token)).isInstanceOf(AdminAuthentication.Authenticated.class);
    }

    @Test
    void a_rejection_never_carries_the_token() {
        String token = tokens.signRaw(claimsWithNestedExtra(DEEP));
        AdminAuthentication result = authenticate(token);
        assertThat(String.valueOf(result)).doesNotContain(token.substring(0, 40)).doesNotContain("[[[[");
    }
}
