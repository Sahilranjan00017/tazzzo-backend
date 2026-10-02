package com.tazzzo.admin.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.common.audit.ActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google OIDC matrix (A-L, R, S) at the authenticator boundary: Stage A verification by Nimbus with a locally
 * generated RSA key and a fixed clock, then Stage B allowlist resolution. No live network.
 */
class GoogleOidcAuthenticatorTest {

    static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final String WRITER = GoogleIdTokens.WRITER;
    static final String READER = GoogleIdTokens.READER;
    static final String DISABLED = GoogleIdTokens.DISABLED;
    static final String STRANGER = GoogleIdTokens.STRANGER;

    final GoogleIdTokens tokens = new GoogleIdTokens("kid-1");
    HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    static AdminAuthProperties properties() {
        return GoogleIdTokens.properties();
    }

    GoogleOidcAuthenticator authenticator(JWKSource<SecurityContext> keys, GoogleOidcSettings settings) {
        HumanAdminSettings s = HumanAdminSettings.from(properties());
        return new GoogleOidcAuthenticator(new GoogleOidcVerifier(settings, keys, CLOCK), s.allowlist(),
                settings.credentialId());
    }

    GoogleOidcAuthenticator authenticator() {
        return authenticator(tokens.trustedKeys(), GoogleIdTokens.settings());
    }

    static AdminBearerCredential bearer(String token) {
        return AdminBearerCredential.fromAuthorizationHeader("Bearer " + token).orElseThrow();
    }

    AdminAuthentication authenticate(String token) {
        return authenticator().authenticate(bearer(token));
    }

    static AdminAuthentication rejected(AdminAuthRejection reason) {
        return new AdminAuthentication.Rejected(reason);
    }

    static AdminPrincipal principal(AdminAuthentication result) {
        assertThat(result).isInstanceOf(AdminAuthentication.Authenticated.class);
        return ((AdminAuthentication.Authenticated) result).principal();
    }

    // ---------- A: valid ----------

    @Test
    void A_a_valid_allowlisted_token_authenticates_a_HUMAN_ADMIN_keyed_by_sub_with_allowlist_roles() {
        AdminPrincipal p = principal(authenticate(tokens.token(WRITER, NOW)));

        assertThat(p.actorType()).isEqualTo(ActorType.HUMAN_ADMIN);
        assertThat(p.actorId()).isEqualTo("google:" + WRITER);
        assertThat(p.credentialId()).isEqualTo(GoogleIdTokens.CREDENTIAL_ID);
        assertThat(p.roles()).containsExactly(AdminPrincipal.CMS_WRITER);
        assertThat(p.canWrite()).isTrue();
        assertThat(p.toString()).doesNotContain("@").doesNotContain("Test Admin");
    }

    @Test
    void A_the_legacy_google_issuer_form_is_accepted() {
        assertThat(principal(authenticate(tokens.token(WRITER, NOW, c -> c.put("iss", "accounts.google.com"))))
                .actorId()).isEqualTo("google:" + WRITER);
    }

    @Test
    void A_an_audience_list_containing_the_admin_client_is_accepted() {
        assertThat(authenticate(tokens.token(WRITER, NOW,
                c -> c.put("aud", List.of("other-client", GoogleIdTokens.AUDIENCE)))))
                .isInstanceOf(AdminAuthentication.Authenticated.class);
    }

    // ---------- B, C, D, E: signature, key and algorithm ----------

    @Test
    void B_a_signature_by_an_untrusted_key_claiming_the_trusted_kid_is_invalid() {
        String forged = GoogleIdTokens.sign(tokens.otherKey, JWSAlgorithm.RS256, tokens.key.getKeyID(),
                GoogleIdTokens.claims(WRITER, NOW));
        assertThat(authenticate(forged)).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void B_a_tampered_payload_under_a_genuine_signature_is_invalid() {
        String genuine = tokens.token(READER, NOW);
        String[] parts = genuine.split("\\.");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace(READER, WRITER).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(authenticate(tampered)).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void C_an_unknown_kid_is_invalid() {
        String unknownKid = GoogleIdTokens.sign(tokens.otherKey, JWSAlgorithm.RS256, tokens.otherKey.getKeyID(),
                GoogleIdTokens.claims(WRITER, NOW));
        assertThat(authenticate(unknownKid)).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void D_alg_none_is_invalid() {
        assertThat(authenticate(GoogleIdTokens.unsigned(WRITER, NOW))).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void E_an_unexpected_asymmetric_algorithm_is_invalid_even_with_the_trusted_key() {
        String rs512 = GoogleIdTokens.sign(tokens.key, JWSAlgorithm.RS512, tokens.key.getKeyID(),
                GoogleIdTokens.claims(WRITER, NOW));
        assertThat(authenticate(rs512)).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void E_an_HS256_token_keyed_with_the_public_key_is_invalid() {
        assertThat(authenticate(tokens.hmacConfusion(WRITER, NOW))).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void malformed_credentials_including_opaque_strings_are_invalid_tokens() {
        for (String junk : new String[]{"not-a-jwt", "a.b.c", "x.y", "....", "eyJhbGciOiJSUzI1NiJ9.e30."}) {
            assertThat(authenticate(junk)).as(junk).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
        }
    }

    // ---------- F, G: time ----------

    @Test
    void F_an_expired_token_is_expired_token() {
        String expired = tokens.token(WRITER, NOW.minusSeconds(7200));
        assertThat(authenticate(expired)).isEqualTo(rejected(AdminAuthRejection.EXPIRED_TOKEN));
        String justPastSkew = tokens.token(WRITER, NOW, c -> c.put("exp", NOW.minusSeconds(61).getEpochSecond()));
        assertThat(authenticate(justPastSkew)).isEqualTo(rejected(AdminAuthRejection.EXPIRED_TOKEN));
    }

    @Test
    void F_expiry_within_the_60s_clock_skew_is_tolerated() {
        String withinSkew = tokens.token(WRITER, NOW.minusSeconds(600), c -> c.put("exp", NOW.minusSeconds(30).getEpochSecond()));
        assertThat(authenticate(withinSkew)).isInstanceOf(AdminAuthentication.Authenticated.class);
    }

    @Test
    void F_a_token_without_exp_is_invalid() {
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.remove("exp"))))
                .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void G_a_token_issued_beyond_the_skew_in_the_future_is_invalid() {
        String future = tokens.token(WRITER, NOW.plusSeconds(120));
        assertThat(authenticate(future)).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
        String withinSkew = tokens.token(WRITER, NOW.plusSeconds(30));
        assertThat(authenticate(withinSkew)).isInstanceOf(AdminAuthentication.Authenticated.class);
    }

    @Test
    void G_a_token_not_yet_valid_by_nbf_is_invalid() {
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("nbf", NOW.plusSeconds(600).getEpochSecond()))))
                .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    // ---------- H, I: issuer and audience ----------

    @Test
    void H_any_other_issuer_is_invalid() {
        for (String iss : new String[]{"https://evil.example", "https://accounts.google.com/", "http://accounts.google.com",
                "https://ACCOUNTS.google.com", "https://securetoken.google.com/project"}) {
            assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("iss", iss)))).as(iss)
                    .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
        }
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.remove("iss"))))
                .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void I_any_other_audience_is_invalid() {
        for (Object aud : new Object[]{"customer-app.apps.googleusercontent.com", "admin-client",
                List.of("mobile-client.apps.googleusercontent.com")}) {
            assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("aud", aud)))).as(String.valueOf(aud))
                    .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
        }
    }

    // ---------- J, K, L, S: email_verified and hosted domain ----------

    @Test
    void J_an_unverified_email_is_email_unverified() {
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("email_verified", false))))
                .isEqualTo(rejected(AdminAuthRejection.EMAIL_UNVERIFIED));
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("email_verified", "false"))))
                .isEqualTo(rejected(AdminAuthRejection.EMAIL_UNVERIFIED));
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.remove("email_verified"))))
                .isEqualTo(rejected(AdminAuthRejection.EMAIL_UNVERIFIED));
    }

    @Test
    void K_a_missing_hd_is_domain_mismatch() {
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.remove("hd"))))
                .isEqualTo(rejected(AdminAuthRejection.DOMAIN_MISMATCH));
    }

    @Test
    void L_a_wrong_hd_is_domain_mismatch_and_the_email_suffix_is_never_consulted() {
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("hd", "other.test"))))
                .isEqualTo(rejected(AdminAuthRejection.DOMAIN_MISMATCH));
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("hd", "TAZZZO.TEST"))))
                .as("exact match").isEqualTo(rejected(AdminAuthRejection.DOMAIN_MISMATCH));
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> {
            c.put("hd", "other.test");
            c.put("email", "writer@" + GoogleIdTokens.DOMAIN);
        }))).as("an in-domain email suffix does not rescue a wrong hd")
                .isEqualTo(rejected(AdminAuthRejection.DOMAIN_MISMATCH));
    }

    @Test
    void S_a_personal_google_account_is_refused_even_when_its_subject_is_allowlisted() {
        String personal = tokens.token(WRITER, NOW, c -> {
            c.remove("hd");
            c.put("email", "someone@gmail.com");
        });
        assertThat(authenticate(personal)).isEqualTo(rejected(AdminAuthRejection.DOMAIN_MISMATCH));
    }

    // ---------- M, N: allowlist (Stage B) ----------

    @Test
    void M_a_valid_in_domain_identity_absent_from_the_allowlist_is_not_allowlisted() {
        assertThat(authenticate(tokens.token(STRANGER, NOW))).isEqualTo(rejected(AdminAuthRejection.NOT_ALLOWLISTED));
    }

    @Test
    void N_an_allowlisted_but_disabled_identity_is_disabled() {
        assertThat(authenticate(tokens.token(DISABLED, NOW))).isEqualTo(rejected(AdminAuthRejection.DISABLED));
    }

    @Test
    void a_blank_or_missing_sub_is_invalid() {
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.remove("sub"))))
                .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
        assertThat(authenticate(tokens.token(WRITER, NOW, c -> c.put("sub", " "))))
                .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    // ---------- R: identity is sub, never email; roles are the allowlist's ----------

    @Test
    void R_the_same_sub_with_a_changed_email_is_the_same_actor() {
        AdminPrincipal before = principal(authenticate(tokens.token(WRITER, NOW, c -> c.put("email", "writer@tazzzo.test"))));
        AdminPrincipal after = principal(authenticate(tokens.token(WRITER, NOW, c -> c.put("email", "renamed@tazzzo.test"))));

        assertThat(after).isEqualTo(before);
        assertThat(after.actorId()).isEqualTo("google:" + WRITER);
    }

    @Test
    void an_email_matching_an_allowlist_label_never_stands_in_for_the_subject() {
        String impostor = tokens.token(STRANGER, NOW, c -> c.put("email", "writer@tazzzo.test"));
        assertThat(authenticate(impostor)).isEqualTo(rejected(AdminAuthRejection.NOT_ALLOWLISTED));
    }

    @Test
    void roles_come_from_the_allowlist_never_from_token_claims() {
        String claimsWriter = tokens.token(READER, NOW, c -> {
            c.put("roles", List.of("cms-writer"));
            c.put("groups", List.of("cms-writer"));
            c.put("scope", "cms-writer");
        });
        AdminPrincipal p = principal(authenticate(claimsWriter));

        assertThat(p.roles()).isEqualTo(Set.of(AdminPrincipal.READER));
        assertThat(p.canWrite()).isFalse();
    }

    @Test
    void a_disabled_authenticator_recognises_nothing() {
        assertThat(GoogleOidcAuthenticator.disabled().authenticate(bearer(tokens.token(WRITER, NOW))))
                .isEqualTo(AdminAuthentication.NOT_APPLICABLE);
    }

    @Test
    void the_credential_never_renders_its_value() {
        String token = tokens.token(WRITER, NOW);
        assertThat(bearer(token).toString()).doesNotContain(token).doesNotContain(token.substring(0, 20));
        assertThat(AdminBearerCredential.fromAuthorizationHeader("Bearer ")).isEmpty();
        assertThat(AdminBearerCredential.fromAuthorizationHeader("Basic abc")).isEmpty();
        assertThat(AdminBearerCredential.fromAuthorizationHeader(null)).isEmpty();
    }

    // ---------- JWKS: outage, caching and rotation, against the PRODUCTION remote key source ----------

    private final AtomicReference<JWKSet> published = new AtomicReference<>();
    private final AtomicInteger fetches = new AtomicInteger();

    private URI startKeyServer(JWKSet initial) throws Exception {
        published.set(initial);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/certs", exchange -> {
            fetches.incrementAndGet();
            byte[] body = published.get().toString(true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
    }

    private static GoogleOidcSettings settingsAt(URI jwks) {
        GoogleOidcSettings s = GoogleIdTokens.settings();
        return new GoogleOidcSettings(s.issuer(), s.audience(), s.hostedDomain(), s.credentialId(), jwks);
    }

    @Test
    void unavailable_keys_fail_closed() throws Exception {
        URI jwks = startKeyServer(tokens.publicJwks());
        server.stop(0);
        server = null;
        GoogleOidcSettings settings = settingsAt(jwks);

        assertThat(authenticator(GoogleOidcVerifier.remoteKeys(settings), settings).authenticate(bearer(tokens.token(WRITER, NOW))))
                .isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
    }

    @Test
    void production_keys_are_cached_refreshed_on_rotation_and_rate_limited_fail_closed() throws Exception {
        GoogleOidcSettings settings = settingsAt(startKeyServer(tokens.publicJwks()));
        GoogleOidcAuthenticator production = authenticator(GoogleOidcVerifier.remoteKeys(settings), settings);

        assertThat(production.authenticate(bearer(tokens.token(WRITER, NOW)))).isInstanceOf(AdminAuthentication.Authenticated.class);
        assertThat(production.authenticate(bearer(tokens.token(READER, NOW)))).isInstanceOf(AdminAuthentication.Authenticated.class);
        assertThat(fetches.get()).as("the key set is fetched once and cached").isEqualTo(1);

        // Rotation: Google publishes a new key; a token under its unknown kid triggers a refresh and verifies.
        published.set(new JWKSet(List.of(tokens.key.toPublicJWK(), tokens.otherKey.toPublicJWK())));
        String rotated = GoogleIdTokens.sign(tokens.otherKey, JWSAlgorithm.RS256, tokens.otherKey.getKeyID(),
                GoogleIdTokens.claims(WRITER, NOW));
        assertThat(production.authenticate(bearer(rotated))).isInstanceOf(AdminAuthentication.Authenticated.class);
        assertThat(fetches.get()).isEqualTo(2);

        // A further unknown kid inside the refresh rate-limit window: no fetch, refused, never trusted unverified.
        RSAKey third = new RSAKeyGenerator(2048).keyID("kid-3").generate();
        published.set(new JWKSet(List.of(tokens.key.toPublicJWK(), tokens.otherKey.toPublicJWK(), third.toPublicJWK())));
        String raced = GoogleIdTokens.sign(third, JWSAlgorithm.RS256, third.getKeyID(), GoogleIdTokens.claims(WRITER, NOW));
        assertThat(production.authenticate(bearer(raced))).isEqualTo(rejected(AdminAuthRejection.INVALID_TOKEN));
        assertThat(fetches.get()).as("rate-limited: Google's endpoint is not hammered by unknown kids").isEqualTo(2);
        assertThat(production.authenticate(bearer(tokens.token(WRITER, NOW))))
                .as("still-cached keys keep verifying").isInstanceOf(AdminAuthentication.Authenticated.class);
    }

    @Test
    void verification_reads_only_the_subject_out_of_the_claims() throws Exception {
        GoogleOidcVerifier verifier = new GoogleOidcVerifier(GoogleIdTokens.settings(), tokens.trustedKeys(), CLOCK);
        assertThat(verifier.verify(tokens.token(WRITER, NOW))).isEqualTo(new GoogleOidcVerifier.VerifiedIdentity(WRITER));
        assertThat(GoogleOidcVerifier.VerifiedIdentity.class.getRecordComponents()).hasSize(1);
    }
}
