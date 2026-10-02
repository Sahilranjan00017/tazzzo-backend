package com.tazzzo.admin.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Stage A of human admin access: is this a genuine Google ID token for OUR admin client, from OUR Workspace? Local
 * cryptographic verification only (never Google's tokeninfo endpoint); no client secret is involved.
 *
 * <p>Order of checks, all by Nimbus except where noted, and nothing read from the payload before the signature holds:
 * <ol>
 *   <li>JWS only, {@code RS256} only ({@code alg=none}, HMAC and any other algorithm are refused by the key selector);
 *       the key is selected by {@code kid} from the trusted key set and the signature verified;</li>
 *   <li>claims: required {@code iss sub aud exp iat}; {@code aud} must contain exactly-configured audience; {@code exp}
 *       and {@code nbf} against the injected clock with {@value #MAX_CLOCK_SKEW_SECONDS}s skew; {@code iss} must be one of
 *       Google's documented issuers (here); {@code iat} no further in the future than the skew (here);</li>
 *   <li>after verification (here): {@code hd} exactly the configured Workspace domain (absent is a mismatch: personal
 *       accounts carry no {@code hd}; the email suffix is never consulted), {@code email_verified} true, {@code sub}
 *       non-blank.</li>
 * </ol>
 * The result is only the stable {@code sub}: email, name, picture and the rest of the claims are dropped here.
 *
 * <p><b>Keys.</b> Production keys come from Google's published JWKS ({@link #remoteKeys}): cached (5 min TTL), refreshed
 * on an unknown {@code kid} (key rotation), rate-limited, retried once. Unavailable keys or an unknown {@code kid} after
 * refresh fail CLOSED ({@link AdminAuthRejection#INVALID_TOKEN}); still-cached keys keep verifying per the cache. No
 * production key is committed to source.
 */
public final class GoogleOidcVerifier {

    static final int MAX_CLOCK_SKEW_SECONDS = 60;
    static final Set<String> GOOGLE_ISSUERS = Set.of(HumanAdminSettings.GOOGLE_ISSUER, "accounts.google.com");
    private static final int MAX_SUBJECT_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(GoogleOidcVerifier.class);

    /** A verified Google identity: only the stable subject survives verification. */
    public record VerifiedIdentity(String subject) {
    }

    /** Thrown for a refused token; carries only the bounded reason. */
    public static final class Rejected extends Exception {
        private final AdminAuthRejection reason;

        Rejected(AdminAuthRejection reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public AdminAuthRejection reason() {
            return reason;
        }
    }

    /** A claim failure decided inside the processor, i.e. strictly AFTER the signature was verified. */
    private static final class ClaimRejected extends BadJWTException {
        private final AdminAuthRejection reason;

        ClaimRejected(AdminAuthRejection reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    private final GoogleOidcSettings settings;
    private final Clock clock;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public GoogleOidcVerifier(GoogleOidcSettings settings, JWKSource<SecurityContext> keys, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        p.setJWTClaimsSetVerifier(new GoogleClaimsVerifier());
        this.processor = p;
    }

    /** Google's key set behind Nimbus' caching, rate-limited, retrying remote source. Never fetched at construction. */
    public static JWKSource<SecurityContext> remoteKeys(GoogleOidcSettings settings) {
        try {
            return JWKSourceBuilder.<SecurityContext>create(settings.jwksUri().toURL()).retrying(true).build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("tazzzo.admin.oidc.jwks-uri is not a URL", e);
        }
    }

    public VerifiedIdentity verify(String idToken) throws Rejected {
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (ClaimRejected e) {
            throw new Rejected(e.reason);
        } catch (ParseException | BadJOSEException e) {
            throw new Rejected(AdminAuthRejection.INVALID_TOKEN);
        } catch (JOSEException e) {
            // Key source failure (JWKS unreachable / unusable): fail closed. Class name only: never a body or token.
            log.warn("admin_oidc_key_source_failed type={}", e.getClass().getSimpleName());
            throw new Rejected(AdminAuthRejection.INVALID_TOKEN);
        } catch (RuntimeException e) {
            log.warn("admin_oidc_verification_failed type={}", e.getClass().getSimpleName());
            throw new Rejected(AdminAuthRejection.INVALID_TOKEN);
        }
        Object hd = claims.getClaim("hd");
        if (!(hd instanceof String domain) || !domain.equals(settings.hostedDomain())) {
            throw new Rejected(AdminAuthRejection.DOMAIN_MISMATCH);
        }
        Object emailVerified = claims.getClaim("email_verified");
        if (!Boolean.TRUE.equals(emailVerified) && !"true".equals(emailVerified)) {
            throw new Rejected(AdminAuthRejection.EMAIL_UNVERIFIED);
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new Rejected(AdminAuthRejection.INVALID_TOKEN);
        }
        return new VerifiedIdentity(subject);
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    /** Nimbus' standard claim checks against OUR clock, then the Google-specific issuer and issued-at checks. */
    private final class GoogleClaimsVerifier implements JWTClaimsSetVerifier<SecurityContext> {

        private final DefaultJWTClaimsVerifier<SecurityContext> standard =
                // Mutable sets on purpose: Nimbus probes them with contains(null), which Set.of rejects.
                new DefaultJWTClaimsVerifier<>(new HashSet<>(Set.of(settings.audience())), null,
                        new HashSet<>(Set.of("iss", "sub", "aud", "exp", "iat")), new HashSet<>()) {
                    @Override
                    protected Date currentTime() {
                        return now();
                    }
                };

        GoogleClaimsVerifier() {
            standard.setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);
        }

        @Override
        public void verify(JWTClaimsSet claims, SecurityContext context) throws BadJWTException {
            long skewMillis = Duration.ofSeconds(MAX_CLOCK_SKEW_SECONDS).toMillis();
            try {
                standard.verify(claims, context);
            } catch (BadJWTException e) {
                Date exp = claims.getExpirationTime();
                if (exp != null && now().getTime() > exp.getTime() + skewMillis) {
                    throw new ClaimRejected(AdminAuthRejection.EXPIRED_TOKEN);
                }
                throw e;
            }
            if (!GOOGLE_ISSUERS.contains(claims.getIssuer())) {
                throw new BadJWTException("issuer rejected");
            }
            if (claims.getIssueTime().getTime() > now().getTime() + skewMillis) {
                throw new BadJWTException("issued in the future");
            }
        }
    }
}
