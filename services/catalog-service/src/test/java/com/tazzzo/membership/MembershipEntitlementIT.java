package com.tazzzo.membership;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Money;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-16A-2 -- the entitlement READ seam, standalone and transactional, against real Mongo. Read only: every test
 * that reads a stale row also proves nothing was mutated.
 */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipEntitlementIT extends AbstractMembershipIT {

    @Autowired MeterRegistry springRegistry;
    @Autowired MembershipEntitlementPort wiredStandalone;
    @Autowired TransactionalMembershipEntitlementPort wiredTransactional;
    @Autowired MembershipService wiredService;

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    // ---------- fixtures ----------

    /** Records which read handle / session the repository was asked to use, and can fail the session read. */
    static class RecordingRepository extends MembershipRepository {
        final AtomicBoolean primaryReadsUsed = new AtomicBoolean();
        final AtomicInteger sessionReads = new AtomicInteger();
        volatile ClientSession sessionSeen;
        volatile MongoException sessionFailure;
        volatile boolean failOnce;

        RecordingRepository(com.mongodb.client.MongoDatabase db) {
            super(db);
        }

        @Override
        com.mongodb.client.MongoCollection<Document> primaryReads() {
            primaryReadsUsed.set(true);
            return super.primaryReads();
        }

        @Override
        public Optional<Membership> findCurrentCandidateByCustomer(ClientSession session, CustomerId customerId) {
            sessionReads.incrementAndGet();
            sessionSeen = session;
            MongoException failure = sessionFailure;
            if (failure != null) {
                if (failOnce) {
                    sessionFailure = null;
                }
                throw failure;
            }
            return super.findCurrentCandidateByCustomer(session, customerId);
        }
    }

    private MembershipEntitlementReader reader(MembershipRepository repo) {
        return new MembershipEntitlementReader(repo, clock);
    }

    private MembershipEntitlementService standalone(MembershipRepository repo) {
        return new MembershipEntitlementService(repo, new MembershipObservability(registry), clock);
    }

    private Optional<MembershipEntitlement> transactional(MembershipEntitlementReader r, CustomerId c) {
        return new Tx(client).call(session -> r.currentEntitlement(session, c));
    }

    private void insertRaw(Document d) {
        db.getCollection(MembershipRepository.COLLECTION).insertOne(d);
    }

    private static Document openTermDoc(CustomerId customer) {
        return MembershipRepository.toDocument(Membership.newGrant(MembershipId.generate(), customer,
                new MembershipGrantReference(GrantSource.INTERNAL_GRANT, newRef()), PLAN, T0));
    }

    private Set<String> membershipMeterIds(MeterRegistry r) {
        return r.getMeters().stream().map(Meter::getId).filter(id -> id.getName().startsWith("membership"))
                .map(id -> id + "=" + r.get(id.getName()).tags(id.getTags()).counter().count())
                .collect(Collectors.toSet());
    }

    private Optional<MembershipEntitlement> wiredInTx(Tx tx, CustomerId customer) {
        return tx.call(session -> wiredTransactional.currentEntitlement(session, customer));
    }

    private static void assertFailure(Runnable call, MembershipFailure.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MembershipFailure.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    // ---------- value type ----------

    @Test
    void the_entitlement_exposes_exactly_four_fields_and_nothing_mutable_or_commercial() {
        assertThat(Arrays.stream(MembershipEntitlement.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("membershipId", "planId", "planVersion", "validUntil");
    }

    // ---------- standalone ----------

    @Test
    void an_active_term_inside_its_window_is_an_entitlement_with_the_stored_term_facts() {
        CustomerId customer = newCustomer();
        Membership term = service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0.plusSeconds(3600));

        Optional<MembershipEntitlement> e = standalone(new MembershipRepository(db)).currentEntitlement(customer);

        assertThat(e).hasValue(new MembershipEntitlement(term.membershipId(), PLAN_ID, 1, T0_UNTIL));
        assertThat(registry.getMeters().stream().filter(m -> m.getId().getName().equals("membership_failure")))
                .as("a normal result is never a failure metric").isEmpty();
    }

    @Test
    void no_membership_is_an_authoritative_empty_and_records_nothing() {
        assertThat(standalone(new MembershipRepository(db)).currentEntitlement(newCustomer())).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void the_runtime_window_is_exact_to_the_millisecond_on_both_ports() {
        CustomerId customer = newCustomer();
        service().grant(customer, PLAN_ID, 1, newRef());
        MembershipRepository repo = new MembershipRepository(db);
        MembershipEntitlementService standalone = standalone(repo);
        MembershipEntitlementReader reader = reader(repo);

        Object[][] cases = {
                {T0.minusMillis(1), false}, {T0, true}, {T0.plusMillis(1), true},
                {T0_UNTIL.minusMillis(1), true}, {T0_UNTIL, false}, {T0_UNTIL.plusMillis(1), false}};
        for (Object[] c : cases) {
            clock.set((Instant) c[0]);
            boolean expected = (Boolean) c[1];
            assertThat(standalone.currentEntitlement(customer).isPresent()).as("standalone at " + c[0]).isEqualTo(expected);
            assertThat(transactional(reader, customer).isPresent()).as("transactional at " + c[0]).isEqualTo(expected);
        }
    }

    @Test
    void a_stale_ACTIVE_row_is_empty_and_the_read_mutates_nothing() {
        CustomerId customer = newCustomer();
        Membership stale = service().grant(customer, PLAN_ID, 1, newRef());
        Document before = raw(stale.membershipId());

        clock.set(T0_UNTIL.plusSeconds(86_400));
        MembershipRepository repo = new MembershipRepository(db);
        assertThat(standalone(repo).currentEntitlement(customer)).isEmpty();
        assertThat(transactional(reader(repo), customer)).isEmpty();

        Document after = raw(stale.membershipId());
        assertThat(after).as("status, version, openTerm, updatedAt and everything else unchanged").isEqualTo(before);
        assertThat(after.getString("status")).isEqualTo("ACTIVE");
        assertThat(after.get("openTerm")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) after.get("version")).longValue()).isEqualTo(1L);
        assertThat(rows(customer)).hasSize(1);
    }

    @Test
    void a_not_yet_started_ACTIVE_row_is_empty_holds_its_slot_and_is_not_mutated() {
        CustomerId customer = newCustomer();
        Membership future = service().grant(customer, PLAN_ID, 1, newRef());
        Document before = raw(future.membershipId());
        clock.set(T0.minusSeconds(5)); // this node's clock is behind the granting node's

        assertThat(standalone(new MembershipRepository(db)).currentEntitlement(customer)).isEmpty();
        assertThat(raw(future.membershipId())).isEqualTo(before);
        assertFailure(() -> service().grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.ALREADY_ACTIVE);
    }

    private static Document expiredDoc(CustomerId customer) {
        Document expired = openTermDoc(customer);
        expired.put("status", "EXPIRED");
        expired.remove("openTerm");
        expired.put("version", 2L);
        expired.put("updatedAt", expired.get("validUntil"));
        return expired;
    }

    @Test
    void a_customer_with_no_membership_or_only_EXPIRED_terms_is_authoritatively_empty() {
        MembershipRepository repo = new MembershipRepository(db);
        assertThat(standalone(repo).currentEntitlement(newCustomer())).isEmpty();

        CustomerId onlyExpired = newCustomer();
        insertRaw(expiredDoc(onlyExpired));
        insertRaw(expiredDoc(onlyExpired));
        insertRaw(expiredDoc(onlyExpired));
        clock.set(T0.plusSeconds(60)); // inside every one of those (historical) windows
        assertThat(rows(onlyExpired)).hasSize(3);
        assertThat(standalone(repo).currentEntitlement(onlyExpired)).as("historical terminal rows are never active").isEmpty();
        assertThat(transactional(reader(repo), onlyExpired)).isEmpty();
        assertThat(registry.getMeters()).as("an authoritative empty is not a failure").isEmpty();
    }

    @Test
    void terminal_history_never_hides_or_replaces_the_one_current_ACTIVE_term() {
        CustomerId customer = newCustomer();
        insertRaw(expiredDoc(customer));
        insertRaw(expiredDoc(customer));
        Membership current = service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0.plusSeconds(60));

        MembershipRepository repo = new MembershipRepository(db);
        MembershipEntitlement expected = new MembershipEntitlement(current.membershipId(), PLAN_ID, 1, T0_UNTIL);
        assertThat(standalone(repo).currentEntitlement(customer)).hasValue(expected);
        assertThat(transactional(reader(repo), customer)).hasValue(expected);
    }

    @Test
    void an_EXPIRED_row_still_carrying_an_open_marker_claims_the_open_slot_and_fails_loud_on_both_ports() {
        CustomerId customer = newCustomer();
        Document impossible = openTermDoc(customer);
        impossible.put("status", "EXPIRED"); // terminal ... but still claiming the open slot with openTerm=true
        insertRaw(impossible);
        clock.set(T0.plusSeconds(60));

        MembershipRepository repo = new MembershipRepository(db);
        assertFailure(() -> standalone(repo).currentEntitlement(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        assertFailure(() -> transactional(reader(repo), customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        assertFailure(() -> service().grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.INTEGRITY_FAILURE);
    }

    @Test
    void a_standalone_read_uses_the_primary_pinned_handle_and_the_session_read_does_not() {
        CustomerId customer = newCustomer();
        service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0.plusSeconds(1));

        RecordingRepository standaloneRepo = new RecordingRepository(db);
        assertThat(standalone(standaloneRepo).currentEntitlement(customer)).isPresent();
        assertThat(standaloneRepo.primaryReadsUsed).as("standalone read goes through primaryReads()").isTrue();
        assertThat(standaloneRepo.sessionReads.get()).isZero();

        RecordingRepository sessionRepo = new RecordingRepository(db);
        assertThat(transactional(reader(sessionRepo), customer)).isPresent();
        assertThat(sessionRepo.primaryReadsUsed).as("the transactional read never falls back to the standalone handle")
                .isFalse();
        assertThat(sessionRepo.sessionReads.get()).isEqualTo(1);
    }

    @Test
    void a_standalone_outage_is_UNAVAILABLE_and_counted_once_never_empty() {
        try (MongoClient broken = MongoClients.create(
                "mongodb://127.0.0.1:1/?connectTimeoutMS=200&serverSelectionTimeoutMS=200")) {
            MembershipEntitlementService svc = standalone(new MembershipRepository(broken.getDatabase("tazzzo_it")));
            assertFailure(() -> svc.currentEntitlement(newCustomer()), MembershipFailure.Reason.UNAVAILABLE);
        }
        assertThat(count("membership_failure", "operation", "entitlement_read", "reason", "unavailable")).isEqualTo(1.0);
    }

    @Test
    void a_null_customer_is_INVALID_REQUEST() {
        assertFailure(() -> standalone(new MembershipRepository(db)).currentEntitlement(null),
                MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> transactional(reader(new MembershipRepository(db)), null),
                MembershipFailure.Reason.INVALID_REQUEST);
    }

    // ---------- corruption stays fail-loud, never an empty entitlement ----------

    private void assertCorruptIsIntegrityFailure(String why, Consumer<Document> corrupt) {
        CustomerId customer = newCustomer();
        Document d = openTermDoc(customer); // keeps openTerm=true, so the structural query returns it
        corrupt.accept(d);
        insertRaw(d);
        MembershipRepository repo = new MembershipRepository(db);
        assertFailure(() -> standalone(repo).currentEntitlement(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        assertFailure(() -> transactional(reader(repo), customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        assertThat(count("membership_failure", "operation", "entitlement_read", "reason", "integrity_failure"))
                .as(why).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void corrupt_persisted_rows_are_INTEGRITY_FAILURE_on_both_ports() {
        clock.set(T0.plusSeconds(60));
        assertCorruptIsIntegrityFailure("invalid billing zone", d -> d.put("billingZoneId", "UTC"));
        assertCorruptIsIntegrityFailure("missing billing zone", d -> d.remove("billingZoneId"));
        assertCorruptIsIntegrityFailure("incorrect validUntil",
                d -> d.put("validUntil", Date.from(T0_UNTIL.plusMillis(1))));
        assertCorruptIsIntegrityFailure("malformed plan snapshot", d -> d.put("planPricePaise", "9900"));
        assertCorruptIsIntegrityFailure("zero plan price", d -> d.put("planPricePaise", 0L));
        assertCorruptIsIntegrityFailure("unknown grant source", d -> d.put("grantSource", "PAYMENT"));
        assertCorruptIsIntegrityFailure("missing plan id", d -> d.remove("planId"));
        assertCorruptIsIntegrityFailure("createdAt != validFrom", d -> d.put("createdAt", Date.from(T0.minusSeconds(1))));
    }

    @Test
    void an_unknown_status_claiming_the_open_slot_is_INTEGRITY_FAILURE_on_both_ports() {
        clock.set(T0.plusSeconds(60));
        // openTerm=true means "this row claims to be the customer's current/open membership": a corrupt status on
        // that slot must fail loud, whatever the status string is (and the write path agrees)
        for (String badStatus : List.of("SUSPENDED", "BROKEN", "active", "Active", "PENDING_ACTIVATION", "")) {
            CustomerId customer = newCustomer();
            Document d = openTermDoc(customer); // keeps openTerm=true
            d.put("status", badStatus);
            insertRaw(d);

            MembershipRepository repo = new MembershipRepository(db);
            assertFailure(() -> standalone(repo).currentEntitlement(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
            assertFailure(() -> transactional(reader(repo), customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
            assertFailure(() -> service().grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.INTEGRITY_FAILURE);
        }
    }

    @Test
    void historical_terminal_garbage_that_does_not_claim_the_open_slot_is_not_audited_by_the_read() {
        // The read audits only rows that ARE or CLAIM the current membership. A row with no openTerm field and a
        // status that is not ACTIVE is history, whatever it contains; it must not turn into a failure or an entitlement.
        CustomerId customer = newCustomer();
        for (String garbage : List.of("BROKEN", "SUSPENDED", "")) {
            Document d = expiredDoc(customer);
            d.put("status", garbage);
            insertRaw(d);
        }
        clock.set(T0.plusSeconds(60));
        MembershipRepository repo = new MembershipRepository(db);
        assertThat(standalone(repo).currentEntitlement(customer)).isEmpty();
        assertThat(transactional(reader(repo), customer)).isEmpty();
    }

    @Test
    void every_corrupt_ACTIVE_open_marker_shape_is_INTEGRITY_FAILURE_on_both_ports_never_empty() {
        clock.set(T0.plusSeconds(60));
        // The entitlement read is the LIFECYCLE query (customerId + status = ACTIVE), so a marker the openTerm
        // slot filter could never match still reaches strict reconstruction -- and is rejected there.
        assertCorruptIsIntegrityFailure("ACTIVE missing openTerm", d -> d.remove("openTerm"));
        assertCorruptIsIntegrityFailure("ACTIVE openTerm=false", d -> d.put("openTerm", false));
        assertCorruptIsIntegrityFailure("ACTIVE openTerm=null", d -> d.put("openTerm", null));
        assertCorruptIsIntegrityFailure("ACTIVE openTerm=\"true\"", d -> d.put("openTerm", "true"));
        assertCorruptIsIntegrityFailure("ACTIVE openTerm=1", d -> d.put("openTerm", 1));
        assertCorruptIsIntegrityFailure("ACTIVE openTerm=document", d -> d.put("openTerm", new Document()));
    }

    @Test
    void more_than_one_current_candidate_is_INTEGRITY_FAILURE_never_pick_one() {
        clock.set(T0.plusSeconds(60));
        MembershipRepository repo = new MembershipRepository(db);
        List<Consumer<Document>> corruptTwins = List.of(
                d -> d.remove("openTerm"),                                   // ACTIVE twin that lost its marker
                d -> d.put("openTerm", false),                               // ACTIVE twin, marker false
                d -> { d.put("status", "REVOKED"); d.put("openTerm", false); }, // REVOKED must never carry the marker, even false
                d -> { d.put("status", "BROKEN"); d.put("openTerm", "true"); }, // unknown status, non-boolean claim
                d -> { d.put("status", "EXPIRED"); d.put("openTerm", null); }); // terminal twin with a null claim
        for (Consumer<Document> corrupt : corruptTwins) {
            CustomerId customer = newCustomer();
            insertRaw(openTermDoc(customer)); // a VALID ACTIVE open term ...
            Document twin = openTermDoc(customer);
            corrupt.accept(twin);             // ... plus a corrupt twin the partial unique index cannot see
            insertRaw(twin);
            assertFailure(() -> standalone(repo).currentEntitlement(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
            assertFailure(() -> transactional(reader(repo), customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        }
    }

    @Test
    void two_rows_both_claiming_open_or_active_state_are_INTEGRITY_FAILURE() {
        clock.set(T0.plusSeconds(60));
        CustomerId customer = newCustomer();
        Document claimsOpenSlot = openTermDoc(customer);
        claimsOpenSlot.put("status", "BROKEN");       // holds the unique open slot (openTerm=true) with a corrupt status
        insertRaw(claimsOpenSlot);
        Document laterActive = openTermDoc(customer); // an ACTIVE row that lost its marker, so the index allows it
        laterActive.remove("openTerm");
        insertRaw(laterActive);

        MembershipRepository repo = new MembershipRepository(db);
        assertFailure(() -> standalone(repo).currentEntitlement(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        assertFailure(() -> transactional(reader(repo), customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
    }

    @Test
    void two_individually_valid_current_candidates_are_INTEGRITY_FAILURE_even_if_the_unique_index_is_missing() {
        // Defence in depth: with membership_one_open_per_customer absent (a deployment/index fault), two rows that are
        // each perfectly valid ACTIVE open terms could coexist. Neither is corrupt on its own, so only the
        // "more than one candidate" rule can catch it -- the read must never pick one arbitrarily.
        CustomerId customer = newCustomer();
        var memberships = db.getCollection(MembershipRepository.COLLECTION);
        memberships.dropIndex("membership_one_open_per_customer");
        try {
            insertRaw(openTermDoc(customer));
            insertRaw(openTermDoc(customer));
            clock.set(T0.plusSeconds(60));

            MembershipRepository repo = new MembershipRepository(db);
            assertFailure(() -> standalone(repo).currentEntitlement(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
            assertFailure(() -> transactional(reader(repo), customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        } finally {
            memberships.deleteMany(new Document("customerId", customer.value()));
            schemaBootstrap.bootstrap(db); // idempotently recreates the dropped index
        }
        assertThat(db.getCollection(MembershipRepository.COLLECTION).listIndexes().into(new java.util.ArrayList<>())
                .stream().map(d -> d.getString("name"))).contains("membership_one_open_per_customer");
    }

    @Test
    void the_lifecycle_read_never_returns_a_stale_or_future_ACTIVE_row_as_corruption() {
        CustomerId customer = newCustomer();
        Membership term = service().grant(customer, PLAN_ID, 1, newRef());
        Document before = raw(term.membershipId());
        MembershipRepository repo = new MembershipRepository(db);

        clock.set(T0_UNTIL.plusSeconds(1)); // valid stale ACTIVE: window ended, marker intact
        assertThat(standalone(repo).currentEntitlement(customer)).isEmpty();
        assertThat(transactional(reader(repo), customer)).isEmpty();
        clock.set(T0.minusSeconds(1));      // valid future-window ACTIVE
        assertThat(standalone(repo).currentEntitlement(customer)).isEmpty();
        assertThat(transactional(reader(repo), customer)).isEmpty();
        assertThat(raw(term.membershipId())).as("zero mutation").isEqualTo(before);
    }

    // ---------- snapshot independence ----------

    @Test
    void an_entitlement_reports_the_stored_term_snapshot_whatever_the_plan_configuration_becomes() {
        CustomerId customer = newCustomer();
        Membership term = service().grant(customer, PLAN_ID, 1, newRef());

        // the plan configuration moves on (v1 closed, v2 priced differently and longer) -- nothing here can
        // reach the read, which has no plan source
        MembershipPlan v1Closed = new MembershipPlan(PLAN_ID, 1, Money.ofInrPaise(9900), 1,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-02-01T00:00:00Z"));
        MembershipPlan v2 = new MembershipPlan(PLAN_ID, 2, Money.ofInrPaise(14900), 3,
                Instant.parse("2027-02-01T00:00:00Z"), null);
        planSource(v1Closed, v2);
        clock.set(T0.plusSeconds(3600));

        MembershipRepository repo = new MembershipRepository(db);
        MembershipEntitlement expected = new MembershipEntitlement(term.membershipId(), PLAN_ID, 1, T0_UNTIL);
        assertThat(standalone(repo).currentEntitlement(customer)).hasValue(expected);
        assertThat(transactional(reader(repo), customer)).hasValue(expected);
        assertThat(Arrays.stream(MembershipEntitlementReader.class.getConstructors()[0].getParameterTypes()))
                .doesNotContain(MembershipPlanSource.class);
        assertThat(Arrays.stream(MembershipEntitlementService.class.getConstructors()[0].getParameterTypes()))
                .doesNotContain(MembershipPlanSource.class);
    }

    // ---------- transactional ----------

    @Test
    void the_transactional_read_joins_the_caller_session_sees_its_own_uncommitted_write_and_opens_no_transaction() {
        CustomerId customer = newCustomer();
        RecordingRepository repo = new RecordingRepository(db);
        MembershipEntitlementReader reader = reader(repo);
        clock.set(T0.plusSeconds(60));
        CountingTx tx = new CountingTx(client);

        Boolean outsideSawItBeforeCommit = tx.call(session -> {
            assertThat(session.hasActiveTransaction()).isTrue();
            assertThat(reader.currentEntitlement(session, customer)).as("nothing yet").isEmpty();

            db.getCollection(MembershipRepository.COLLECTION).insertOne(session, openTermDoc(customer));

            assertThat(reader.currentEntitlement(session, customer)).as("the caller's own write is visible").isPresent();
            assertThat(session.hasActiveTransaction()).as("still the caller's one transaction").isTrue();
            assertThat(repo.sessionSeen).as("the PROVIDED session was used").isSameAs(session);
            // a standalone read cannot see the uncommitted row: proof the port did not fall back to it
            return standalone(new MembershipRepository(db)).currentEntitlement(customer).isPresent();
        });

        assertThat(outsideSawItBeforeCommit).isFalse();
        assertThat(tx.calls.get()).as("the port opened no transaction of its own").isEqualTo(1);
        assertThat(standalone(new MembershipRepository(db)).currentEntitlement(customer)).as("visible once committed").isPresent();
    }

    @Test
    void the_transactional_read_reports_present_empty_and_stale_correctly() {
        CustomerId customer = newCustomer();
        MembershipEntitlementReader reader = reader(new MembershipRepository(db));
        assertThat(transactional(reader, customer)).isEmpty();

        Membership term = service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0.plusSeconds(1));
        assertThat(transactional(reader, customer)).hasValue(
                new MembershipEntitlement(term.membershipId(), PLAN_ID, 1, T0_UNTIL));

        clock.set(T0_UNTIL.plusSeconds(1));
        assertThat(transactional(reader, customer)).isEmpty();
    }

    @Test
    void a_non_transient_datastore_failure_in_the_session_read_is_a_typed_UNAVAILABLE() {
        RecordingRepository repo = new RecordingRepository(db);
        repo.sessionFailure = new MongoException("simulated session read failure");
        MembershipEntitlementReader reader = reader(repo);
        assertFailure(() -> transactional(reader, newCustomer()), MembershipFailure.Reason.UNAVAILABLE);
    }

    @Test
    void a_transient_transaction_error_propagates_untouched_so_the_callers_retry_still_works() {
        CustomerId customer = newCustomer();
        service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0.plusSeconds(1));

        RecordingRepository repo = new RecordingRepository(db);
        MongoException transientError = new MongoException("simulated transient error");
        transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        repo.sessionFailure = transientError;
        repo.failOnce = true;
        MembershipEntitlementReader reader = reader(repo);
        CountingTx tx = new CountingTx(client);
        AtomicInteger attempts = new AtomicInteger();

        // the driver re-runs the WHOLE callback because the label survived; attempt 2 reads normally
        Optional<MembershipEntitlement> e = tx.call(session -> {
            attempts.incrementAndGet();
            return reader.currentEntitlement(session, customer);
        });

        assertThat(e).isPresent();
        assertThat(attempts.get()).as("the caller's retry loop saw the transient label").isEqualTo(2);
    }

    // ---------- zero metrics from the transactional port ----------

    @Test
    void the_transactional_port_emits_zero_metrics_on_success_empty_failure_and_across_a_caller_retry() {
        CustomerId entitled = newCustomer();
        wiredService.grant(entitled, PLAN_ID, 1, newRef()); // real clock; plan effective since 2026-01-01
        CustomerId nobody = newCustomer();
        CustomerId corrupt = newCustomer();
        Document bad = MembershipRepository.toDocument(Membership.newGrant(MembershipId.generate(), corrupt,
                new MembershipGrantReference(GrantSource.INTERNAL_GRANT, newRef()), PLAN,
                Instant.now().minusSeconds(60)));
        bad.put("billingZoneId", "UTC");
        insertRaw(bad);

        Set<String> baseline = membershipMeterIds(springRegistry);
        Tx tx = new Tx(client);

        assertThat(wiredInTx(tx, entitled)).as("success").isPresent();
        assertThat(wiredInTx(tx, nobody)).as("empty").isEmpty();
        assertThatThrownBy(() -> wiredInTx(tx, corrupt)).as("failure").isInstanceOf(MembershipFailure.class);
        RetryInjectingTx retrying = new RetryInjectingTx(client);
        retrying.arm(1);
        assertThat(wiredInTx(retrying, entitled)).as("retry").isPresent();
        assertThat(retrying.attempts()).isEqualTo(2);

        assertThat(membershipMeterIds(springRegistry)).as("no membership meter created or incremented")
                .isEqualTo(baseline);
    }

    @Test
    void the_standalone_port_records_only_a_failure_metric_and_never_an_entitlement_result_metric() {
        CustomerId entitled = newCustomer();
        wiredService.grant(entitled, PLAN_ID, 1, newRef());
        Set<String> afterGrant = membershipMeterIds(springRegistry);

        assertThat(wiredStandalone.currentEntitlement(entitled)).isPresent();
        assertThat(wiredStandalone.currentEntitlement(newCustomer())).isEmpty();

        assertThat(membershipMeterIds(springRegistry)).as("present and empty record nothing").isEqualTo(afterGrant);
        assertThat(springRegistry.getMeters().stream().map(m -> m.getId().getName()))
                .noneMatch(n -> n.contains("entitlement") && !n.equals("membership_failure"));
    }

    @Test
    void the_production_wiring_resolves_each_port_to_its_own_implementation() {
        assertThat(wiredStandalone).isInstanceOf(MembershipEntitlementService.class);
        assertThat(wiredTransactional).isInstanceOf(MembershipEntitlementReader.class);
        assertThat(List.of(wiredStandalone.getClass().getInterfaces())).doesNotContain(TransactionalMembershipEntitlementPort.class);
    }
}
