package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.SessionId;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * PR-11C — orchestrates: OTP verified grant -&gt; customer resolution/creation -&gt; session
 * creation -&gt; access/refresh token issuance, plus refresh rotation and logout. This class owns
 * exactly these transitions — it does not touch customer profile/address/cart, and it never derives
 * customer identity from anything but a validated grant or an already-authenticated
 * {@link CustomerPrincipal} (never phone input, installationId, or a client-supplied customerId).
 *
 * <p><b>Durability</b> (the PR-11B lesson applied again): grant consumption, customer
 * resolve-or-create, and session creation are ONE Mongo transaction via {@link Tx} — the SAME
 * primitive {@code OtpService}'s verify() and {@code PricingService}'s write path already use. Any
 * step that must cause a rollback THROWS INSIDE the transaction callback; a result is never checked
 * only after {@code tx.run()} returns.
 *
 * <p>No direct wall-clock read anywhere in this class — every timestamp comes from the injected
 * {@link Clock}, read ONCE per call.
 */
@Service
public class CustomerSessionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSessionService.class);

    private final OtpVerifiedGrantRepository grants;
    private final CustomerRepository customers;
    private final CustomerSessionRepository sessions;
    private final RefreshTokenCodec refreshCodec;
    private final CustomerAccessTokenCodec accessCodec;
    private final CustomerSessionProperties properties;
    private final Clock clock;
    private final Tx tx;

    public CustomerSessionService(OtpVerifiedGrantRepository grants, CustomerRepository customers,
                                  CustomerSessionRepository sessions, RefreshTokenCodec refreshCodec,
                                  CustomerAccessTokenCodec accessCodec, CustomerSessionProperties properties,
                                  Clock clock, Tx tx) {
        this.grants = grants;
        this.customers = customers;
        this.sessions = sessions;
        this.refreshCodec = refreshCodec;
        this.accessCodec = accessCodec;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    public SessionEstablishResult establishSession(String grantId) {
        if (grantId == null || grantId.isBlank() || grantId.length() > 128) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID_REQUEST);
        }
        refreshCodec.requireReady();
        accessCodec.requireReady();

        Instant now = clock.instant();
        Instant sessionExpiresAt = now.plusSeconds(properties.getSessionTtlSeconds());
        String candidateCustomerId = CustomerId.generate().value();
        SessionId sessionId = SessionId.generate();
        String refreshSecret = refreshCodec.generateSecret();

        Document[] resultHolder = new Document[2]; // [0]=customer, [1]=session
        try {
            tx.run(session -> {
                // FIRST write in this transaction: a null result means nothing has been written
                // yet, so throwing here needs no rollback of anything (mirrors the PR-11B lesson —
                // only a write AFTER this one needs the "throw inside the callback" discipline to
                // force a rollback of what already ran).
                Document grant = grants.consume(session, grantId, OtpPurpose.LOGIN, now);
                if (grant == null) {
                    throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID);
                }
                Phone phone = new Phone(grant.getString("phoneNormalized"));
                Document customer = customers.resolveOrCreate(session, phone, now, candidateCustomerId);
                byte[] refreshDigest = refreshCodec.digest(sessionId, refreshSecret);
                Document sessionDoc = sessions.create(session, sessionId, new CustomerId(customer.getString("_id")),
                        now, sessionExpiresAt, refreshDigest);
                resultHolder[0] = customer;
                resultHolder[1] = sessionDoc;
            });
        } catch (SessionAuthFailure e) {
            log.warn("session_create_failure reason={}", e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("session_create_transaction_failed", e);
            throw new SessionAuthFailure(SessionAuthFailure.Reason.UNAVAILABLE);
        }

        CustomerId customerId = new CustomerId(resultHolder[0].getString("_id"));
        CustomerPrincipal principal = new CustomerPrincipal(customerId, sessionId);
        String accessToken = accessCodec.issue(principal, Duration.ofSeconds(properties.getAccessTokenTtlSeconds()));
        String refreshToken = refreshCodec.format(sessionId, refreshSecret);
        log.info("session_create_success");
        return new SessionEstablishResult(customerId.value(), accessToken,
                properties.getAccessTokenTtlSeconds(), refreshToken);
    }

    public RefreshResult refresh(String refreshToken) {
        RefreshTokenCodec.ParsedRefreshToken parsed = refreshCodec.parse(refreshToken);
        refreshCodec.requireReady();
        accessCodec.requireReady();

        Instant now = clock.instant();
        Document existing = sessions.findById(parsed.sessionId().value());
        if (existing == null) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID);
        }
        byte[] storedDigest = java.util.Base64.getDecoder().decode(existing.getString("refreshTokenDigest"));
        if (!refreshCodec.matches(storedDigest, parsed.sessionId(), parsed.secret())) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID);
        }
        String presentedDigestBase64 = existing.getString("refreshTokenDigest");
        String newSecret = refreshCodec.generateSecret();
        byte[] newDigest = refreshCodec.digest(parsed.sessionId(), newSecret);

        Document[] rotatedHolder = new Document[1];
        try {
            tx.run(session -> {
                Document rotated = sessions.tryRotateRefresh(session, parsed.sessionId(), presentedDigestBase64,
                        newDigest, now);
                if (rotated == null) {
                    // Lost the CAS race, or the session was revoked/expired between the read above
                    // and here — this is the FIRST (only) write attempted in this transaction, so
                    // there is nothing to roll back; throwing simply aborts cleanly.
                    throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID);
                }
                rotatedHolder[0] = rotated;
            });
        } catch (SessionAuthFailure e) {
            log.warn("refresh_failure reason={}", e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("refresh_transaction_failed", e);
            throw new SessionAuthFailure(SessionAuthFailure.Reason.UNAVAILABLE);
        }

        CustomerId customerId = new CustomerId(rotatedHolder[0].getString("customerId"));
        CustomerPrincipal principal = new CustomerPrincipal(customerId, parsed.sessionId());
        String accessToken = accessCodec.issue(principal, Duration.ofSeconds(properties.getAccessTokenTtlSeconds()));
        String newRefreshToken = refreshCodec.format(parsed.sessionId(), newSecret);
        log.info("refresh_success");
        return new RefreshResult(accessToken, properties.getAccessTokenTtlSeconds(), newRefreshToken);
    }

    /** Idempotent: repeated logout is safe and never reveals whether the session was already revoked. */
    public void logout(CustomerPrincipal principal) {
        sessions.revoke(principal.sessionId().value(), clock.instant());
        log.info("logout");
    }

    public record SessionEstablishResult(String customerId, String accessToken, long accessTokenExpiresIn,
                                         String refreshToken) {
    }

    public record RefreshResult(String accessToken, long accessTokenExpiresIn, String refreshToken) {
    }
}
