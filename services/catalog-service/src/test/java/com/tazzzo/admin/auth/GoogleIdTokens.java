package com.tazzzo.admin.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Deterministic Google-style ID tokens signed by a LOCALLY generated RSA key: tests never touch Google's network. The
 * default token is valid for {@link #AUDIENCE} in {@link #DOMAIN}; each test changes exactly the claim it is about.
 */
public final class GoogleIdTokens {

    public static final String AUDIENCE = "admin-client.apps.googleusercontent.com";
    public static final String DOMAIN = "tazzzo.test";
    public static final String LABEL = "cms-test";
    public static final String CREDENTIAL_ID = "oidc:google:" + LABEL;

    /** Allowlisted cms-writer, allowlisted reader, allowlisted-but-disabled, and a valid identity nobody listed. */
    public static final String WRITER = "110000000000000000001";
    public static final String READER = "110000000000000000002";
    public static final String DISABLED = "110000000000000000003";
    public static final String AUDITOR = "110000000000000000005";
    public static final String STRANGER = "110000000000000000099";
    public static final String WRITER_EMAIL_LABEL = "writer@tazzzo.test";

    /** The complete OIDC configuration with the four allowlist entries above (the auditor holds only audit-reader). */
    public static AdminAuthProperties properties() {
        AdminAuthProperties p = new AdminAuthProperties();
        p.getOidc().setIssuer(HumanAdminSettings.GOOGLE_ISSUER);
        p.getOidc().setAudience(AUDIENCE);
        p.getOidc().setHostedDomain(DOMAIN);
        p.getOidc().setCredentialLabel(LABEL);
        p.setUsers(List.of(
                user(WRITER, WRITER_EMAIL_LABEL, List.of("cms-writer"), true),
                user(READER, "reader@tazzzo.test", List.of("reader"), true),
                user(DISABLED, "gone@tazzzo.test", List.of("cms-writer"), false),
                user(AUDITOR, "auditor@tazzzo.test", List.of("audit-reader"), true)));
        return p;
    }

    public static AdminAuthProperties.User user(String sub, String email, List<String> roles, boolean enabled) {
        AdminAuthProperties.User u = new AdminAuthProperties.User();
        u.setProvider("google");
        u.setSubject(sub);
        u.setEmail(email);
        u.setRoles(roles);
        u.setEnabled(enabled);
        return u;
    }

    /** An ENABLED authenticator over the trusted local keys, the fixture allowlist and {@code clock}. */
    public GoogleOidcAuthenticator authenticator(java.time.Clock clock) {
        return new GoogleOidcAuthenticator(new GoogleOidcVerifier(settings(), trustedKeys(), clock),
                HumanAdminSettings.from(properties()).allowlist(), CREDENTIAL_ID);
    }

    public final RSAKey key;
    public final RSAKey otherKey;

    public GoogleIdTokens(String kid) {
        this.key = generate(kid);
        this.otherKey = generate(kid + "-other");
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The trusted public key set (what Google's JWKS endpoint would publish). */
    public JWKSet publicJwks() {
        return new JWKSet(key.toPublicJWK());
    }

    public JWKSource<SecurityContext> trustedKeys() {
        return new ImmutableJWKSet<>(publicJwks());
    }

    public static GoogleOidcSettings settings() {
        return new GoogleOidcSettings(HumanAdminSettings.GOOGLE_ISSUER, AUDIENCE, DOMAIN, CREDENTIAL_ID,
                HumanAdminSettings.GOOGLE_JWKS_URI);
    }

    /** Valid claims for {@code sub} issued at {@code now}, expiring an hour later. */
    public static Map<String, Object> claims(String sub, Instant now) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://accounts.google.com");
        c.put("azp", AUDIENCE);
        c.put("aud", AUDIENCE);
        c.put("sub", sub);
        c.put("hd", DOMAIN);
        c.put("email", "admin." + sub + "@" + DOMAIN);
        c.put("email_verified", true);
        c.put("name", "Test Admin");
        c.put("picture", "https://example.invalid/p.png");
        c.put("iat", now.getEpochSecond());
        c.put("exp", now.plusSeconds(3600).getEpochSecond());
        return c;
    }

    public String token(String sub, Instant now) {
        return token(sub, now, c -> { });
    }

    public String token(String sub, Instant now, Consumer<Map<String, Object>> change) {
        Map<String, Object> c = claims(sub, now);
        change.accept(c);
        return sign(key, JWSAlgorithm.RS256, key.getKeyID(), c);
    }

    public static String sign(RSAKey signer, JWSAlgorithm alg, String kid, Map<String, Object> claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(kid).build(), toClaims(claims));
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RS256 by the trusted key over an exact JSON payload (for claim shapes the claims builder cannot express). */
    public String signRaw(String jsonPayload) {
        try {
            com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new com.nimbusds.jose.Payload(jsonPayload));
            jws.sign(new RSASSASigner(key));
            return jws.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HS256 keyed with the trusted PUBLIC key bytes: the classic RS256 -> HS256 algorithm-confusion attack. */
    public String hmacConfusion(String sub, Instant now) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(),
                    toClaims(claims(sub, now)));
            jwt.sign(new MACSigner(key.toRSAPublicKey().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code alg=none}: an unsecured JWT carrying otherwise-valid claims. */
    public static String unsigned(String sub, Instant now) {
        return new PlainJWT(toClaims(claims(sub, now))).serialize();
    }

    private static JWTClaimsSet toClaims(Map<String, Object> claims) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
        claims.forEach((name, value) -> {
            switch (name) {
                case "iat" -> b.issueTime(new Date(((Number) value).longValue() * 1000));
                case "exp" -> b.expirationTime(new Date(((Number) value).longValue() * 1000));
                case "aud" -> b.audience(value instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                        : List.of(String.valueOf(value)));
                default -> b.claim(name, value);
            }
        });
        return b.build();
    }
}
