package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.ActorType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared SERVICE_ACCOUNT tokens keep their exact semantics behind the constant-time comparison. */
class ServiceTokenAuthenticatorTest {

    static AdminAuthentication auth(ServiceTokenAuthenticator a, String token) {
        return a.authenticate(AdminBearerCredential.fromAuthorizationHeader("Bearer " + token).orElseThrow());
    }

    @Test
    void each_token_maps_to_its_service_account_principal() {
        ServiceTokenAuthenticator a = new ServiceTokenAuthenticator("cms-tok", "read-tok");
        assertThat(auth(a, "cms-tok")).isEqualTo(new AdminAuthentication.Authenticated(
                AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER)));
        assertThat(auth(a, "read-tok")).isEqualTo(new AdminAuthentication.Authenticated(
                AdminPrincipal.sharedToken(AdminPrincipal.READER)));
        assertThat(((AdminAuthentication.Authenticated) auth(a, "cms-tok")).principal().actorType())
                .isEqualTo(ActorType.SERVICE_ACCOUNT);
    }

    @Test
    void identically_configured_tokens_resolve_to_the_cms_writer() {
        ServiceTokenAuthenticator a = new ServiceTokenAuthenticator("same", "same");
        assertThat(auth(a, "same")).isEqualTo(new AdminAuthentication.Authenticated(
                AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER)));
    }

    @Test
    void anything_else_is_not_applicable_and_unset_tokens_disable_their_role() {
        ServiceTokenAuthenticator a = new ServiceTokenAuthenticator("cms-tok", "");
        for (String t : new String[]{"cms-to", "cms-tok ", "CMS-TOK", "cms-tokx", "read-tok", "x"}) {
            assertThat(auth(a, t)).as(t).isEqualTo(AdminAuthentication.NOT_APPLICABLE);
        }
        ServiceTokenAuthenticator none = new ServiceTokenAuthenticator(null, "  ");
        assertThat(auth(none, "anything")).isEqualTo(AdminAuthentication.NOT_APPLICABLE);
    }

    @Test
    void the_chain_tries_the_exact_service_token_first_and_never_routes_by_token_shape() {
        GoogleIdTokens tokens = new GoogleIdTokens("kid-chain");
        java.time.Instant now = java.time.Instant.now();
        // A JWT-SHAPED service token: shape routing would send it to OIDC; the exact service-token match must win.
        String jwtShapedServiceToken = tokens.token("110000000000000000001", now);
        java.util.concurrent.atomic.AtomicInteger keyLookups = new java.util.concurrent.atomic.AtomicInteger();
        com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> countingKeys = (selector, ctx) -> {
            keyLookups.incrementAndGet();
            return selector.select(tokens.publicJwks());
        };
        GoogleOidcAuthenticator oidc = new GoogleOidcAuthenticator(
                new GoogleOidcVerifier(GoogleIdTokens.settings(), countingKeys, java.time.Clock.systemUTC()),
                HumanAdminSettings.from(GoogleOidcAuthenticatorTest.properties()).allowlist(), GoogleIdTokens.CREDENTIAL_ID);
        AdminAuthenticatorChain chain = new AdminAuthenticatorChain(
                new ServiceTokenAuthenticator(jwtShapedServiceToken, "read-tok"), oidc);

        AdminAuthentication service = chain.authenticate(
                AdminBearerCredential.fromAuthorizationHeader("Bearer " + jwtShapedServiceToken).orElseThrow());
        assertThat(service).isEqualTo(new AdminAuthentication.Authenticated(AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER)));
        assertThat(keyLookups.get()).as("a service-token match never reaches OIDC").isZero();

        AdminAuthentication human = chain.authenticate(AdminBearerCredential.fromAuthorizationHeader(
                "Bearer " + tokens.token("110000000000000000002", now)).orElseThrow());
        assertThat(((AdminAuthentication.Authenticated) human).principal().actorType()).isEqualTo(ActorType.HUMAN_ADMIN);
        assertThat(keyLookups.get()).isEqualTo(1);

        assertThat(chain.authenticate(AdminBearerCredential.fromAuthorizationHeader("Bearer opaque-unknown").orElseThrow()))
                .as("an unmatched opaque credential is still judged by OIDC, not skipped by shape")
                .isEqualTo(new AdminAuthentication.Rejected(AdminAuthRejection.INVALID_TOKEN));
        assertThat(new AdminAuthenticatorChain(new ServiceTokenAuthenticator("cms-tok", "read-tok"),
                GoogleOidcAuthenticator.disabled()).authenticate(
                AdminBearerCredential.fromAuthorizationHeader("Bearer opaque-unknown").orElseThrow()))
                .as("with OIDC disabled nothing recognises it").isEqualTo(AdminAuthentication.NOT_APPLICABLE);
    }
}
