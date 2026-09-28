package com.tazzzo.customer.profile;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.auth.session.CustomerIdentityAuthorityImpl;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-12A — {@code CustomerProfileService}/{@code CustomerProfileRepository} exercised directly
 * (no HTTP, no auth) over a real Mongo (Testcontainers). No sleeps: concurrency is pinned with
 * latches, time with a mutable {@link Clock} — the SAME conventions {@code CustomerSessionServiceIT}
 * established. {@code customerId} fixtures here are synthetic and never exist in the auth-owned
 * {@code customers} collection, so the {@link CustomerIdentityAuthority} check (Finding 1) is
 * deliberately stubbed to always succeed — this class tests profile/version logic in isolation.
 * The REAL identity-integrity behavior (a missing/ghost customer identity) is covered separately by
 * {@code CustomerIdentityIntegrityHttpIT}, which does NOT stub the authority.
 */
@SpringBootTest(classes = {CatalogApplication.class, CustomerProfileServiceIT.TestBeans.class})
class CustomerProfileServiceIT extends AbstractMongoIT {

    static final AtomicReference<Instant> CLOCK_NOW = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return CLOCK_NOW.get(); }
            };
        }

        @Bean
        @Primary
        CustomerIdentityAuthority alwaysExistsAuthority() {
            return new CustomerIdentityAuthority() {
                @Override public boolean exists(CustomerId customerId) { return true; }
                @Override public boolean exists(ClientSession session, CustomerId customerId) { return true; }
            };
        }
    }

    @Autowired CustomerProfileService service;
    @Autowired CustomerProfileRepository repository;
    @Autowired Clock clock;
    @Autowired Tx tx;
    @Autowired CustomerProfileObservability observability;
    @Autowired ObjectProvider<CustomerIdentityAuthority> identityAuthorityProvider;
    @Autowired CustomerIdentityAuthorityImpl realIdentityAuthority;

    @BeforeEach
    void resetClock() {
        CLOCK_NOW.set(Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static CustomerId uniqueCustomerId(String suffix) {
        return new CustomerId("CUS_profileit" + suffix);
    }

    private Document rawDoc(CustomerId customerId) {
        return repository.findById(customerId.value());
    }

    /** Seeds a REAL minimal auth-owned customer identity document, matching the shape
     *  {@code CustomerRepository.resolveOrCreate} would have produced. */
    private void seedRealCustomerIdentity(CustomerId customerId) {
        db.getCollection("customers").insertOne(new Document()
                .append("_id", customerId.value())
                .append("phoneNormalized", "+91-fixture-" + customerId.value())
                .append("status", "ACTIVE")
                .append("createdAt", Date.from(Instant.now()))
                .append("updatedAt", Date.from(Instant.now())));
    }

    private void deleteRealCustomerIdentity(CustomerId customerId) {
        db.getCollection("customers").deleteOne(Filters.eq("_id", customerId.value()));
    }

    /** A service instance wired with the REAL {@link CustomerIdentityAuthorityImpl} (not this
     *  class's always-succeeds stub) -- used by the tests that specifically exercise identity
     *  integrity against genuine {@code customers} documents. */
    private CustomerProfileService serviceWithRealIdentityAuthority() {
        return new CustomerProfileService(repository, clock, observability,
                new FixedObjectProvider<>(realIdentityAuthority), tx);
    }

    // ---------- A: absent profile default ----------

    @Test void a_brand_new_customer_has_a_default_profile_at_version_zero() {
        CustomerId customerId = uniqueCustomerId("0001");
        CustomerProfileService.ProfileView view = service.get(customerId);
        assertThat(view.customerId()).isEqualTo(customerId.value());
        assertThat(view.displayName()).isNull();
        assertThat(view.email()).isNull();
        assertThat(view.version()).isZero();
        assertThat(rawDoc(customerId)).as("GET must never persist a document").isNull();
    }

    // ---------- D/E: create then read back ----------

    @Test void d_e_patch_at_version_zero_creates_version_one_and_get_reflects_it() {
        CustomerId customerId = uniqueCustomerId("0002");
        CustomerProfileService.ProfileView created =
                service.patch(customerId, 0, PatchField.of("Sahil Ranjan"), PatchField.absent());
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.displayName()).isEqualTo("Sahil Ranjan");
        assertThat(created.email()).isNull();

        CustomerProfileService.ProfileView fetched = service.get(customerId);
        assertThat(fetched.version()).isEqualTo(1);
        assertThat(fetched.displayName()).isEqualTo("Sahil Ranjan");
    }

    // ---------- F/G: partial patch leaves the other field untouched ----------

    @Test void f_partial_patch_of_display_name_only_leaves_email_unchanged() {
        CustomerId customerId = uniqueCustomerId("0003");
        service.patch(customerId, 0, PatchField.absent(), PatchField.of("a@example.com"));
        CustomerProfileService.ProfileView updated =
                service.patch(customerId, 1, PatchField.of("Megha Namdeo"), PatchField.absent());
        assertThat(updated.displayName()).isEqualTo("Megha Namdeo");
        assertThat(updated.email()).isEqualTo("a@example.com");
    }

    @Test void g_partial_patch_of_email_only_leaves_display_name_unchanged() {
        CustomerId customerId = uniqueCustomerId("0004");
        service.patch(customerId, 0, PatchField.of("José"), PatchField.absent());
        CustomerProfileService.ProfileView updated =
                service.patch(customerId, 1, PatchField.absent(), PatchField.of("Jose@Example.com"));
        assertThat(updated.displayName()).isEqualTo("José");
        assertThat(updated.email()).isEqualTo("jose@example.com"); // canonical lower-case
    }

    // ---------- H/I: explicit null clears ----------

    @Test void h_explicit_null_clears_display_name() {
        CustomerId customerId = uniqueCustomerId("0005");
        service.patch(customerId, 0, PatchField.of("李明"), PatchField.of("li@example.com"));
        CustomerProfileService.ProfileView cleared =
                service.patch(customerId, 1, PatchField.of(null), PatchField.absent());
        assertThat(cleared.displayName()).isNull();
        assertThat(cleared.email()).isEqualTo("li@example.com");
    }

    @Test void i_explicit_null_clears_email() {
        CustomerId customerId = uniqueCustomerId("0006");
        service.patch(customerId, 0, PatchField.of("Name"), PatchField.of("name@example.com"));
        CustomerProfileService.ProfileView cleared =
                service.patch(customerId, 1, PatchField.absent(), PatchField.of(null));
        assertThat(cleared.email()).isNull();
        assertThat(cleared.displayName()).isEqualTo("Name");
    }

    // ---------- K/L: invalid field values ----------

    @Test void k_display_name_over_80_code_points_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0007");
        String tooLong = "x".repeat(81);
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.of(tooLong), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.INVALID_REQUEST));
        assertThat(rawDoc(customerId)).as("a rejected patch must not create a document").isNull();
    }

    @Test void k_display_name_with_a_control_character_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0008");
        String withControlChar = "Sahil" + '\u0007' + "Ranjan";
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.of(withControlChar), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.INVALID_REQUEST));
    }

    @Test void l_malformed_email_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0009");
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.absent(), PatchField.of("not-an-email")))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.INVALID_REQUEST));
    }

    @Test void l_email_with_internal_whitespace_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0010");
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.absent(), PatchField.of("a b@example.com")))
                .isInstanceOf(CustomerProfileFailure.class);
    }

    // ---------- M: stale If-Match / precondition failed ----------

    @Test void m_stale_expected_version_is_rejected_and_state_is_unchanged() {
        CustomerId customerId = uniqueCustomerId("0011");
        service.patch(customerId, 0, PatchField.of("Original"), PatchField.absent());
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.of("Attacker"), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.PRECONDITION_FAILED));
        assertThat(service.get(customerId).displayName()).isEqualTo("Original");
    }

    // ---------- T: clock discipline ----------

    @Test void t_created_at_and_updated_at_come_from_the_injected_clock() {
        CustomerId customerId = uniqueCustomerId("0012");
        Instant createInstant = Instant.parse("2026-07-01T10:00:00Z");
        CLOCK_NOW.set(createInstant);
        service.patch(customerId, 0, PatchField.of("Name"), PatchField.absent());
        Document doc = rawDoc(customerId);
        assertThat(doc.getDate("createdAt").toInstant()).isEqualTo(createInstant);
        assertThat(doc.getDate("updatedAt").toInstant()).isEqualTo(createInstant);

        Instant updateInstant = Instant.parse("2026-08-01T11:00:00Z");
        CLOCK_NOW.set(updateInstant);
        service.patch(customerId, 1, PatchField.absent(), PatchField.of("clock@example.com"));
        Document updated = rawDoc(customerId);
        assertThat(updated.getDate("createdAt").toInstant())
                .as("createdAt never changes on update").isEqualTo(createInstant);
        assertThat(updated.getDate("updatedAt").toInstant()).isEqualTo(updateInstant);
    }

    // ---------- N: concurrent update at the same version -- exactly one winner ----------

    @Test void n_two_concurrent_patches_at_the_same_version_yield_exactly_one_winner() throws Exception {
        CustomerId customerId = uniqueCustomerId("0013");
        service.patch(customerId, 0, PatchField.of("Original"), PatchField.absent());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            for (String candidate : List.of("Alice", "Bob")) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        results.add(service.patch(customerId, 1, PatchField.of(candidate), PatchField.absent()));
                    } catch (CustomerProfileFailure e) {
                        results.add(e);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        long successes = results.stream().filter(r -> r instanceof CustomerProfileService.ProfileView).count();
        long failures = results.stream().filter(r -> r instanceof CustomerProfileFailure f
                && f.reason() == CustomerProfileFailure.Reason.PRECONDITION_FAILED).count();
        assertThat(successes).as("exactly one winner").isEqualTo(1);
        assertThat(failures).as("exactly one loser, as a 412").isEqualTo(1);

        CustomerProfileService.ProfileView finalState = service.get(customerId);
        assertThat(finalState.version()).isEqualTo(2);
        assertThat(finalState.displayName()).isIn("Alice", "Bob");
    }

    // ---------- O: concurrent first-create -- exactly one winner, only one document ----------

    @Test void o_two_concurrent_first_creates_yield_exactly_one_winner_and_one_document() throws Exception {
        CustomerId customerId = uniqueCustomerId("0014");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            for (String candidate : List.of("First", "Second")) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        results.add(service.patch(customerId, 0, PatchField.of(candidate), PatchField.absent()));
                    } catch (CustomerProfileFailure e) {
                        results.add(e);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        long successes = results.stream().filter(r -> r instanceof CustomerProfileService.ProfileView).count();
        long failures = results.stream().filter(r -> r instanceof CustomerProfileFailure f
                && f.reason() == CustomerProfileFailure.Reason.PRECONDITION_FAILED).count();
        assertThat(successes).as("exactly one create winner").isEqualTo(1);
        assertThat(failures).as("exactly one loser, as a 412").isEqualTo(1);
        assertThat(service.get(customerId).version()).isEqualTo(1);
    }

    // ---------- TOCTOU close: identity check + profile write share ONE transaction ----------

    /** Minimum required case: a customer identity missing from the very start of the transaction
     *  must abort before any write, using the REAL {@link CustomerIdentityAuthorityImpl} against a
     *  real (never-seeded) {@code customers} collection -- no stub. */
    @Test void p_missing_customer_identity_at_transaction_start_yields_503_and_no_document() {
        CustomerId customerId = uniqueCustomerId("0015"); // deliberately never seeded into `customers`
        CustomerProfileService realService = serviceWithRealIdentityAuthority();

        assertThatThrownBy(() -> realService.patch(customerId, 0, PatchField.of("Ghost"), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.UNAVAILABLE));

        assertThat(rawDoc(customerId)).as("no ghost profile is ever created").isNull();
    }

    /** Controls the exact interleaving: the PATCH transaction's identity check reads "exists=true"
     *  (the identity genuinely exists at that point), THEN — strictly afterwards, confirmed via
     *  latch — the identity is deleted and that deletion is fully committed, THEN the paused PATCH
     *  transaction is allowed to proceed to its profile write. This is option A from the mission:
     *  the PATCH transaction is legitimately ordered BEFORE the deletion (its own read already
     *  observed existence), so it may commit -- and if it does, the persisted profile document must
     *  be fully consistent (never a torn/partial write). The forbidden outcome -- a transaction that
     *  never observed existence nonetheless committing a profile -- cannot occur here since the
     *  identity check is the FIRST operation inside the transaction, on the SAME session as the
     *  write that follows it. */
    @Test void q_identity_deleted_strictly_after_the_in_transaction_existence_check_still_commits_consistently()
            throws Exception {
        CustomerId customerId = uniqueCustomerId("0016");
        seedRealCustomerIdentity(customerId);

        CountDownLatch identityCheckPassed = new CountDownLatch(1);
        CountDownLatch identityDeleted = new CountDownLatch(1);

        CustomerProfileRepository pausingRepository = new CustomerProfileRepository(db) {
            @Override
            public Document patch(ClientSession session, String custId, long expectedVersion,
                                  PatchField<String> displayName, PatchField<String> email, Instant now) {
                // The identity check (the first operation in the transaction) has already run by
                // the time this method is reached -- signal it, then wait for the concurrent
                // delete to fully commit before proceeding with the write.
                identityCheckPassed.countDown();
                try {
                    assertThat(identityDeleted.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.patch(session, custId, expectedVersion, displayName, email, now);
            }
        };
        CustomerProfileService racingService = new CustomerProfileService(pausingRepository, clock, observability,
                new FixedObjectProvider<>(realIdentityAuthority), tx);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var patchFuture = pool.submit(() ->
                    racingService.patch(customerId, 0, PatchField.of("Racing"), PatchField.absent()));
            var deleteFuture = pool.submit(() -> {
                assertThat(identityCheckPassed.await(5, TimeUnit.SECONDS)).isTrue();
                deleteRealCustomerIdentity(customerId);
                identityDeleted.countDown();
                return null;
            });

            deleteFuture.get(10, TimeUnit.SECONDS);
            CustomerProfileService.ProfileView result = patchFuture.get(10, TimeUnit.SECONDS);

            // Option A: the transaction's own read already observed the identity, so it is
            // legitimately ordered BEFORE the deletion and its commit is fully consistent.
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.displayName()).isEqualTo("Racing");
            Document persisted = rawDoc(customerId);
            assertThat(persisted).as("the committed transaction's write is durably persisted").isNotNull();
            assertThat(persisted.get("version", Number.class).longValue()).isEqualTo(1);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---------- Tx retry safety: no stale result from an earlier retried attempt ----------

    /** {@code ClientSession.withTransaction} retries its body on a
     *  TransientTransactionError-labeled exception. Proves the profile PATCH result always reflects
     *  the LAST (successful) execution -- never a value from the failed first attempt -- because
     *  {@link Tx#call} returns T straight from the driver, with no mutable holder in between. */
    @Test void r_transaction_retry_returns_the_result_of_the_successful_attempt_not_stale_data() {
        CustomerId customerId = uniqueCustomerId("0017");
        seedRealCustomerIdentity(customerId);
        AtomicInteger attempts = new AtomicInteger(0);

        CustomerProfileRepository retryOnceRepository = new CustomerProfileRepository(db) {
            @Override
            public Document patch(ClientSession session, String custId, long expectedVersion,
                                  PatchField<String> displayName, PatchField<String> email, Instant now) {
                if (attempts.incrementAndGet() == 1) {
                    MongoException transient1 = new MongoException("simulated transient transaction conflict");
                    transient1.addLabel("TransientTransactionError");
                    throw transient1;
                }
                return super.patch(session, custId, expectedVersion, displayName, email, now);
            }
        };
        CustomerProfileService retryingService = new CustomerProfileService(retryOnceRepository, clock, observability,
                new FixedObjectProvider<>(realIdentityAuthority), tx);

        CustomerProfileService.ProfileView result =
                retryingService.patch(customerId, 0, PatchField.of("Retried"), PatchField.absent());

        assertThat(attempts.get()).as("the driver actually retried the transaction body").isEqualTo(2);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.displayName()).isEqualTo("Retried");

        Document persisted = rawDoc(customerId);
        assertThat(persisted.get("version", Number.class).longValue())
                .as("the returned result matches what is actually persisted -- no stale first-attempt data")
                .isEqualTo(result.version());
        assertThat(persisted.getString("displayName")).isEqualTo(result.displayName());
    }

    // ---------- PII-safe logging on PATCH's transactional path ----------

    @Test void s_a_patch_repository_outage_never_logs_the_raw_exception_message() {
        CustomerId customerId = uniqueCustomerId("0018");
        seedRealCustomerIdentity(customerId);
        String fakePiiMessage = "pii@example.com CUS_sensitive Secret Name";

        CustomerProfileRepository throwingRepository = new CustomerProfileRepository(db) {
            @Override
            public Document patch(ClientSession session, String custId, long expectedVersion,
                                  PatchField<String> displayName, PatchField<String> email, Instant now) {
                throw new RuntimeException(fakePiiMessage);
            }
        };
        CustomerProfileService throwingService = new CustomerProfileService(throwingRepository, clock, observability,
                new FixedObjectProvider<>(realIdentityAuthority), tx);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(CustomerProfileService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            assertThatThrownBy(() -> throwingService.patch(customerId, 0, PatchField.of("X"), PatchField.absent()))
                    .isInstanceOf(CustomerProfileFailure.class);

            StringBuilder logged = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                logged.append(event.getFormattedMessage()).append('\n');
                if (event.getThrowableProxy() != null) {
                    logged.append(event.getThrowableProxy().getMessage()).append('\n');
                }
            }
            assertThat(logged.toString()).doesNotContain(fakePiiMessage).doesNotContain("pii@example.com")
                    .doesNotContain("CUS_sensitive").doesNotContain("Secret Name");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
}
