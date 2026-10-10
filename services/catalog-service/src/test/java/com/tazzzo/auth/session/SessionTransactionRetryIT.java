package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-11D — session establish / refresh transaction retry safety over a real Mongo transaction whose
 * commit "fails transiently" ({@link RetryInjectingTx}) so the driver re-invokes the callback.
 */
@SpringBootTest(classes = {CatalogApplication.class, SessionTransactionRetryIT.TestBeans.class})
class SessionTransactionRetryIT extends AbstractMongoIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY;
    // Deliberately in the FUTURE: documents written with this clock land in TTL-indexed collections
    // (expireAfter 0 on expiresAt). A base in the real past makes the Mongo TTL monitor delete them
    // mid-test (flaky EXPIRED->INVALID). Must stay later than any real wall-clock time CI can reach.
    static final AtomicReference<Instant> CLOCK_NOW = new AtomicReference<>(Instant.parse("2099-06-01T00:00:00Z"));

    static {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) 1);
        REFRESH_KEY = Base64.getEncoder().encodeToString(k);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
        r.add("tazzzo.customer-auth.session.access-token-ttl-seconds", () -> "900");
        r.add("tazzzo.customer-auth.session.session-ttl-seconds", () -> "2592000");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return CLOCK_NOW.get(); }
            };
        }
    }

    @Autowired CustomerSessionService plainService;
    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired CustomerRepository customers;
    @Autowired CustomerSessionRepository sessions;
    @Autowired RefreshTokenCodec refreshCodec;
    @Autowired CustomerAccessTokenCodec accessCodec;
    @Autowired CustomerSessionProperties properties;
    @Autowired Clock clock;
    @Autowired SessionObservability observability;
    @Autowired MeterRegistry registry;

    private RetryInjectingTx retryTx;
    private CustomerSessionService retryService;

    @BeforeEach
    void setUp() {
        CLOCK_NOW.set(Instant.parse("2099-06-01T00:00:00Z"));
        retryTx = new RetryInjectingTx(client);
        retryService = new CustomerSessionService(grants, customers, sessions, refreshCodec, accessCodec, properties,
                clock, retryTx, observability);
    }

    private String seedGrant(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, CLOCK_NOW.get(),
                CLOCK_NOW.get().plusSeconds(300));
        return grantId;
    }

    private double count(String name) {
        return registry.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private long customersWithPhone(String phone) {
        return db.getCollection(CustomerRepository.COLLECTION).countDocuments(Filters.eq("phoneNormalized", phone));
    }

    // ---------- establishSession ----------

    @Test void establish_retry_creates_exactly_one_customer_one_session_and_consumes_the_grant_once() {
        String phone = "+919876542001";
        String grantId = seedGrant(phone);
        double successBefore = count("session_create_success");

        retryTx.arm(1);
        CustomerSessionService.SessionEstablishResult r = retryService.establishSession(grantId);

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(retryTx.attemptResults()).as("both attempts used the same retry-stable candidate id")
                .containsExactly(r.customerId(), r.customerId());
        assertThat(customersWithPhone(phone)).isEqualTo(1);
        Document customer = db.getCollection(CustomerRepository.COLLECTION).find(Filters.eq("phoneNormalized", phone)).first();
        assertThat(customer.getString("_id")).isEqualTo(r.customerId());
        assertThat(db.getCollection(SessionCollections.SESSIONS).countDocuments(Filters.eq("customerId", r.customerId())))
                .isEqualTo(1);
        assertThat(db.getCollection(OtpVerifiedGrantRepository.COLLECTION).find(Filters.eq("_id", grantId)).first()
                .get("consumedAt")).as("grant consumed").isNotNull();
        CustomerPrincipal principal = accessCodec.verify(r.accessToken());
        assertThat(principal.customerId().value()).isEqualTo(r.customerId());
        assertThat(count("session_create_success") - successBefore).as("success counted once").isEqualTo(1);
        // the grant is one-time: a second establish fails, nothing more is created
        assertThatThrownBy(() -> plainService.establishSession(grantId)).isInstanceOf(SessionAuthFailure.class);
        assertThat(customersWithPhone(phone)).isEqualTo(1);
    }

    @Test void establish_retry_whose_second_attempt_loses_the_grant_leaves_no_customer_no_session_no_tokens() {
        String phone = "+919876542002";
        String grantId = seedGrant(phone);
        double successBefore = count("session_create_success");
        long sessionsBefore = db.getCollection(SessionCollections.SESSIONS).countDocuments();

        retryTx.arm(1, n -> {
            if (n == 2) { // a competitor consumes the grant between attempts
                db.getCollection(OtpVerifiedGrantRepository.COLLECTION).updateOne(Filters.eq("_id", grantId),
                        Updates.set("consumedAt", java.util.Date.from(CLOCK_NOW.get())));
            }
        });
        assertThatThrownBy(() -> retryService.establishSession(grantId))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));

        assertThat(customersWithPhone(phone)).as("attempt 1's customer was rolled back").isZero();
        assertThat(db.getCollection(SessionCollections.SESSIONS).countDocuments()).isEqualTo(sessionsBefore);
        assertThat(count("session_create_success")).isEqualTo(successBefore);
    }

    // ---------- refresh ----------

    @Test void refresh_retry_returns_the_committed_rotation_and_only_the_new_token_works() {
        CustomerSessionService.SessionEstablishResult est = plainService.establishSession(seedGrant("+919876542003"));
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(5));
        double refreshBefore = count("refresh_success");

        retryTx.arm(1);
        CustomerSessionService.RefreshResult rotated = retryService.refresh(est.refreshToken());

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(count("refresh_success") - refreshBefore).as("success counted once").isEqualTo(1);
        assertThat(accessCodec.verify(rotated.accessToken()).customerId().value()).isEqualTo(est.customerId());
        // the DB holds the digest of the token that was RETURNED (no stale attempt-1 rotation)
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(5));
        CustomerSessionService.RefreshResult next = plainService.refresh(rotated.refreshToken());
        assertThat(next.refreshToken()).isNotEqualTo(rotated.refreshToken());
        assertThatThrownBy(() -> plainService.refresh(est.refreshToken())).isInstanceOf(SessionAuthFailure.class);
    }

    @Test void refresh_retry_that_loses_to_a_committed_competitor_returns_no_stale_rotation() {
        CustomerSessionService.SessionEstablishResult est = plainService.establishSession(seedGrant("+919876542004"));
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(5));
        double refreshBefore = count("refresh_success");
        List<CustomerSessionService.RefreshResult> competitor = new ArrayList<>();

        retryTx.arm(1, n -> {
            if (n == 2) { // a competing refresh with the SAME token commits between attempts
                competitor.add(plainService.refresh(est.refreshToken()));
            }
        });
        assertThatThrownBy(() -> retryService.refresh(est.refreshToken()))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));

        assertThat(competitor).hasSize(1);
        assertThat(count("refresh_success") - refreshBefore).as("only the competitor succeeded").isEqualTo(1);
        // the durable rotation is the competitor's, not attempt 1's rolled-back one
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(5));
        assertThat(plainService.refresh(competitor.get(0).refreshToken()).accessToken()).isNotBlank();
    }

    /** Collection-name indirection so this test does not depend on repository internals beyond the constant. */
    private static final class SessionCollections {
        static final String SESSIONS = CustomerSessionRepository.COLLECTION;
    }
}
