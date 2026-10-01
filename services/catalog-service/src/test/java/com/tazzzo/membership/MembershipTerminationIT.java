package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-16A-3 -- cancel-at-period-end and immediate revoke, against real Mongo. Every assertion about state reads the
 * persisted document, not just the returned value.
 */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipTerminationIT extends AbstractMembershipIT {

    private static final Instant MID = T0.plusSeconds(3600);

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    private MembershipTerminationService termination() {
        return termination(new Tx(client), new MembershipRepository(db));
    }

    private MembershipTerminationService termination(Tx tx, MembershipRepository repo) {
        return new MembershipTerminationService(repo, new MembershipObservability(registry), clock, tx);
    }

    private MembershipEntitlementService entitlement() {
        return new MembershipEntitlementService(new MembershipRepository(db), new MembershipObservability(registry), clock);
    }

    private Optional<MembershipEntitlement> transactionalEntitlement(CustomerId c) {
        MembershipEntitlementReader reader = new MembershipEntitlementReader(new MembershipRepository(db), clock);
        return new Tx(client).call(session -> reader.currentEntitlement(session, c));
    }

    private static void assertFailure(Runnable call, MembershipFailure.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MembershipFailure.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private Membership granted(CustomerId customer) {
        return service().grant(customer, PLAN_ID, 1, newRef());
    }

    private boolean inTx(java.util.function.Function<com.mongodb.client.ClientSession, Boolean> body) {
        return new Tx(client).call(body);
    }

    private static long versionOf(Document d) {
        return ((Number) d.get("version")).longValue();
    }

    // =============================== cancel-at-period-end ===============================

    @Test
    void cancel_records_cancelRequestedAt_and_changes_nothing_else_of_the_term() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        Document before = raw(term.membershipId());

        clock.set(MID);
        Membership cancelled = termination().cancelAtPeriodEnd(customer);

        Document after = raw(term.membershipId());
        assertThat(cancelled.cancelRequestedAt()).isEqualTo(MID);
        assertThat(after.get("cancelRequestedAt")).isEqualTo(Date.from(MID));
        assertThat(after.getString("status")).as("still ACTIVE").isEqualTo("ACTIVE");
        assertThat(after.get("openTerm")).as("openTerm kept").isEqualTo(Boolean.TRUE);
        assertThat(after.get("validUntil")).as("validUntil NOT shortened").isEqualTo(Date.from(T0_UNTIL));
        assertThat(after.containsKey("revokedAt")).isFalse();
        assertThat(versionOf(after)).isEqualTo(2L);
        assertThat(after.get("updatedAt")).isEqualTo(Date.from(MID));
        // the snapshot, window and identity fields are byte-for-byte untouched
        Document stripped = new Document(after);
        for (String changed : new String[] {"cancelRequestedAt", "version", "updatedAt"}) {
            stripped.remove(changed);
        }
        Document expected = new Document(before);
        for (String changed : new String[] {"version", "updatedAt"}) {
            expected.remove(changed);
        }
        assertThat(stripped).isEqualTo(expected);
        assertThat(count("membership_operation_success", "operation", "cancel_at_period_end")).isEqualTo(1.0);
        assertThat(count("membership_transition")).as("a cancel request is not a status transition").isZero();
    }

    @Test
    void entitlement_continues_after_a_cancel_request_until_the_window_ends() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        termination().cancelAtPeriodEnd(customer);

        MembershipEntitlement expected = new MembershipEntitlement(term.membershipId(), PLAN_ID, 1, T0_UNTIL);
        for (Instant at : new Instant[] {MID, MID.plusSeconds(86_400), T0_UNTIL.minusMillis(1)}) {
            clock.set(at);
            assertThat(entitlement().currentEntitlement(customer)).as("standalone at " + at).hasValue(expected);
            assertThat(transactionalEntitlement(customer)).as("transactional at " + at).hasValue(expected);
        }
        clock.set(T0_UNTIL);
        assertThat(entitlement().currentEntitlement(customer)).as("the window ended: cancellation takes effect").isEmpty();
        assertThat(transactionalEntitlement(customer)).isEmpty();
    }

    @Test
    void a_repeated_cancel_is_idempotent_and_never_rewrites_the_timestamp_or_the_version() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        Membership first = termination().cancelAtPeriodEnd(customer);
        Document afterFirst = raw(term.membershipId());

        clock.set(MID.plusSeconds(600));
        Membership second = termination().cancelAtPeriodEnd(customer);
        clock.set(MID.plusSeconds(1200));
        Membership third = termination().cancelAtPeriodEnd(customer);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(second.cancelRequestedAt()).as("the ORIGINAL timestamp").isEqualTo(MID);
        assertThat(raw(term.membershipId())).as("no mutation at all, not even a version/updatedAt bump").isEqualTo(afterFirst);
        assertThat(count("membership_operation_success", "operation", "cancel_at_period_end")).isEqualTo(3.0);
    }

    @Test
    void cancel_with_no_current_membership_is_NOT_FOUND() {
        assertFailure(() -> termination().cancelAtPeriodEnd(newCustomer()), MembershipFailure.Reason.NOT_FOUND);
        assertThat(count("membership_failure", "operation", "cancel_at_period_end", "reason", "not_found")).isEqualTo(1.0);
    }

    @Test
    void cancel_of_a_stale_or_not_yet_started_ACTIVE_term_is_INVALID_TRANSITION_and_mutates_nothing() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        Document before = raw(term.membershipId());

        clock.set(T0_UNTIL); // stale: the window ended, the row is still persisted ACTIVE
        assertFailure(() -> termination().cancelAtPeriodEnd(customer), MembershipFailure.Reason.INVALID_TRANSITION);
        clock.set(T0.minusMillis(1)); // future window (clock skew)
        assertFailure(() -> termination().cancelAtPeriodEnd(customer), MembershipFailure.Reason.INVALID_TRANSITION);

        assertThat(raw(term.membershipId())).as("neither rewritten as REVOKED nor expired nor cancelled").isEqualTo(before);
        assertThat(count("membership_failure", "operation", "cancel_at_period_end", "reason", "invalid_transition"))
                .isEqualTo(2.0);
    }

    @Test
    void an_already_recorded_cancel_replays_as_success_even_after_the_window_ended() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        Membership first = termination().cancelAtPeriodEnd(customer);

        Document before = raw(term.membershipId());

        clock.set(T0_UNTIL.plusSeconds(5)); // stale now, but the request is a recorded fact (replay wins)
        assertThat(termination().cancelAtPeriodEnd(customer)).isEqualTo(first);
        Document after = raw(term.membershipId());
        assertThat(after.get("cancelRequestedAt")).isEqualTo(Date.from(MID));
        assertThat(after).as("replay performs NO mutation: version, updatedAt, validUntil, status, openTerm unchanged")
                .isEqualTo(before);
        assertThat(after.get("openTerm")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void cancel_over_a_corrupt_current_candidate_is_INTEGRITY_FAILURE() {
        clock.set(MID);
        for (java.util.function.Consumer<Document> corrupt : java.util.List.<java.util.function.Consumer<Document>>of(
                d -> d.remove("openTerm"), d -> d.put("openTerm", false), d -> d.put("billingZoneId", "UTC"),
                d -> d.put("status", "BROKEN"), d -> { d.put("status", "REVOKED"); }, d -> d.put("revokedAt", Date.from(MID)))) {
            CustomerId customer = newCustomer();
            Document d = MembershipRepository.toDocument(Membership.newGrant(MembershipId.generate(), customer,
                    new MembershipGrantReference(GrantSource.INTERNAL_GRANT, newRef()), PLAN, T0));
            corrupt.accept(d);
            db.getCollection(MembershipRepository.COLLECTION).insertOne(d);
            assertFailure(() -> termination().cancelAtPeriodEnd(customer), MembershipFailure.Reason.INTEGRITY_FAILURE);
        }
    }

    @Test
    void cancel_after_the_term_was_revoked_finds_no_current_membership() {
        // REVOKED rows carry no open marker by design, so the customer has no current/open term: NOT_FOUND
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        termination().revoke(term.membershipId());
        assertFailure(() -> termination().cancelAtPeriodEnd(customer), MembershipFailure.Reason.NOT_FOUND);
    }

    // =============================== immediate revoke ===============================

    @Test
    void revoke_sets_REVOKED_and_revokedAt_and_REMOVES_the_open_marker() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        Document before = raw(term.membershipId());

        clock.set(MID);
        Membership revoked = termination().revoke(term.membershipId());

        Document after = raw(term.membershipId());
        assertThat(revoked.status()).isEqualTo(MembershipStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(MID);
        assertThat(after.getString("status")).isEqualTo("REVOKED");
        assertThat(after.get("revokedAt")).isEqualTo(Date.from(MID));
        assertThat(after.containsKey("openTerm")).as("openTerm is $unset -- never false/null").isFalse();
        assertThat(after.containsKey("cancelRequestedAt")).isFalse();
        assertThat(versionOf(after)).isEqualTo(2L);
        assertThat(after.get("updatedAt")).isEqualTo(Date.from(MID));
        assertThat(after.get("validUntil")).as("the window fact itself is untouched").isEqualTo(before.get("validUntil"));
        assertThat(MembershipRepository.toMembership(after)).isEqualTo(revoked);
        assertThat(count("membership_operation_success", "operation", "revoke")).isEqualTo(1.0);
        assertThat(count("membership_transition", "from", "active", "to", "revoked")).isEqualTo(1.0);
    }

    @Test
    void revoke_removes_the_entitlement_immediately_on_both_ports() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        assertThat(entitlement().currentEntitlement(customer)).isPresent();

        termination().revoke(term.membershipId());

        assertThat(entitlement().currentEntitlement(customer)).isEmpty(); // same instant, no waiting
        assertThat(transactionalEntitlement(customer)).isEmpty();
        clock.set(MID.minusSeconds(1800)); // even if the clock were earlier: REVOKED is never an entitlement
        assertThat(entitlement().currentEntitlement(customer)).isEmpty();
    }

    @Test
    void a_repeated_revoke_is_idempotent_and_keeps_the_first_revokedAt() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        Membership first = termination().revoke(term.membershipId());
        Document afterFirst = raw(term.membershipId());

        clock.set(MID.plusSeconds(900));
        Membership second = termination().revoke(term.membershipId());
        clock.set(T0_UNTIL.plusSeconds(5)); // even after the window: REVOKED stays REVOKED
        Membership third = termination().revoke(term.membershipId());

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(second.revokedAt()).isEqualTo(MID);
        assertThat(raw(term.membershipId())).isEqualTo(afterFirst);
        assertThat(count("membership_operation_success", "operation", "revoke")).isEqualTo(3.0);
        assertThat(count("membership_transition", "from", "active", "to", "revoked"))
                .as("only the mutating call is a transition").isEqualTo(1.0);
    }

    @Test
    void revoke_of_an_unknown_membership_is_NOT_FOUND() {
        assertFailure(() -> termination().revoke(MembershipId.generate()), MembershipFailure.Reason.NOT_FOUND);
    }

    @Test
    void revoke_of_an_EXPIRED_term_is_INVALID_TRANSITION() {
        CustomerId customer = newCustomer();
        Membership old = granted(customer);
        clock.set(T0_UNTIL.plusSeconds(60));
        granted(customer); // lazily expires the old term
        Document before = raw(old.membershipId());
        assertThat(before.getString("status")).isEqualTo("EXPIRED");

        assertFailure(() -> termination().revoke(old.membershipId()), MembershipFailure.Reason.INVALID_TRANSITION);
        assertThat(raw(old.membershipId())).isEqualTo(before);
    }

    @Test
    void revoke_of_a_stale_or_not_yet_started_ACTIVE_term_is_INVALID_TRANSITION_and_never_rewritten() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        Document before = raw(term.membershipId());

        clock.set(T0_UNTIL);
        assertFailure(() -> termination().revoke(term.membershipId()), MembershipFailure.Reason.INVALID_TRANSITION);
        clock.set(T0.minusMillis(1));
        assertFailure(() -> termination().revoke(term.membershipId()), MembershipFailure.Reason.INVALID_TRANSITION);

        assertThat(raw(term.membershipId())).isEqualTo(before);
        assertThat(count("membership_failure", "operation", "revoke", "reason", "invalid_transition")).isEqualTo(2.0);
    }

    @Test
    void revoke_over_a_corrupt_row_is_INTEGRITY_FAILURE() {
        clock.set(MID);
        for (java.util.function.Consumer<Document> corrupt : java.util.List.<java.util.function.Consumer<Document>>of(
                d -> d.put("billingZoneId", "UTC"), d -> d.remove("openTerm"), d -> d.put("status", "BROKEN"),
                d -> d.put("revokedAt", Date.from(MID)),
                d -> { d.put("status", "REVOKED"); d.remove("openTerm"); } /* REVOKED without revokedAt */)) {
            Membership m = Membership.newGrant(MembershipId.generate(), newCustomer(),
                    new MembershipGrantReference(GrantSource.INTERNAL_GRANT, newRef()), PLAN, T0);
            Document d = MembershipRepository.toDocument(m);
            corrupt.accept(d);
            db.getCollection(MembershipRepository.COLLECTION).insertOne(d);
            assertFailure(() -> termination().revoke(m.membershipId()), MembershipFailure.Reason.INTEGRITY_FAILURE);
        }
    }

    // =============================== cancel then revoke ===============================

    @Test
    void revoke_after_a_cancel_request_preserves_cancelRequestedAt_as_history() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        termination().cancelAtPeriodEnd(customer);

        clock.set(MID.plusSeconds(600));
        Membership revoked = termination().revoke(term.membershipId());

        Document after = raw(term.membershipId());
        assertThat(revoked.cancelRequestedAt()).isEqualTo(MID);
        assertThat(revoked.revokedAt()).isEqualTo(MID.plusSeconds(600));
        assertThat(after.get("cancelRequestedAt")).isEqualTo(Date.from(MID));
        assertThat(after.get("revokedAt")).isEqualTo(Date.from(MID.plusSeconds(600)));
        assertThat(after.getString("status")).isEqualTo("REVOKED");
        assertThat(after.containsKey("openTerm")).isFalse();
        assertThat(versionOf(after)).isEqualTo(3L);
        assertThat(MembershipRepository.toMembership(after)).isEqualTo(revoked);
    }

    @Test
    void a_revoke_never_restores_or_recreates_the_open_marker() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        termination().revoke(term.membershipId());

        assertFailure(() -> termination().cancelAtPeriodEnd(customer), MembershipFailure.Reason.NOT_FOUND);
        termination().revoke(term.membershipId());
        entitlement().currentEntitlement(customer);
        transactionalEntitlement(customer);

        Document after = raw(term.membershipId());
        assertThat(after.containsKey("openTerm")).isFalse();
        assertThat(after.getString("status")).isEqualTo("REVOKED");
        assertThat(versionOf(after)).isEqualTo(2L);
        assertThat(rows(customer).stream().filter(r -> r.containsKey("openTerm"))).isEmpty();
    }

    @Test
    void a_revoked_term_frees_the_slot_for_a_new_grant_and_stays_revoked() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        Membership term = service().grant(customer, PLAN_ID, 1, ref);
        clock.set(MID);
        termination().revoke(term.membershipId());

        Membership fresh = service().grant(customer, PLAN_ID, 1, newRef());

        assertThat(fresh.membershipId()).isNotEqualTo(term.membershipId());
        assertThat(rows(customer).stream().filter(r -> r.containsKey("openTerm"))).hasSize(1);
        assertThat(raw(term.membershipId()).getString("status")).isEqualTo("REVOKED");
        assertThat(service().grant(customer, PLAN_ID, 1, ref).status())
                .as("replay of the revoked term's reference returns that REVOKED term").isEqualTo(MembershipStatus.REVOKED);
        assertThat(entitlement().currentEntitlement(customer).orElseThrow().membershipId()).isEqualTo(fresh.membershipId());
    }

    // =============================== validation / outage / retry ===============================

    @Test
    void null_arguments_are_INVALID_REQUEST() {
        assertFailure(() -> termination().cancelAtPeriodEnd(null), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> termination().revoke(null), MembershipFailure.Reason.INVALID_REQUEST);
    }

    @Test
    void a_datastore_outage_is_a_typed_UNAVAILABLE_for_both_commands() {
        MembershipTerminationService svc = termination(new OutageTx(client), new MembershipRepository(db));
        assertFailure(() -> svc.cancelAtPeriodEnd(newCustomer()), MembershipFailure.Reason.UNAVAILABLE);
        assertFailure(() -> svc.revoke(MembershipId.generate()), MembershipFailure.Reason.UNAVAILABLE);
        assertThat(count("membership_failure", "operation", "cancel_at_period_end", "reason", "unavailable")).isEqualTo(1.0);
        assertThat(count("membership_failure", "operation", "revoke", "reason", "unavailable")).isEqualTo(1.0);
        assertThat(count("membership_operation_success")).isZero();
    }

    @Test
    void a_retried_transaction_mutates_exactly_once_and_records_one_metric() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        RetryInjectingTx retrying = new RetryInjectingTx(client);
        MembershipTerminationService svc = termination(retrying, new MembershipRepository(db));

        retrying.arm(1); // attempt 1 applies its writes then "fails transiently": rolled back and re-run
        svc.cancelAtPeriodEnd(customer);
        assertThat(retrying.attempts()).isEqualTo(2);
        assertThat(versionOf(raw(term.membershipId()))).as("one effective cancellation").isEqualTo(2L);
        assertThat(count("membership_operation_success", "operation", "cancel_at_period_end")).isEqualTo(1.0);

        retrying.arm(1);
        svc.revoke(term.membershipId());
        assertThat(retrying.attempts()).isEqualTo(2);
        Document after = raw(term.membershipId());
        assertThat(versionOf(after)).as("one effective revoke").isEqualTo(3L);
        assertThat(after.getString("status")).isEqualTo("REVOKED");
        assertThat(count("membership_operation_success", "operation", "revoke")).isEqualTo(1.0);
        assertThat(count("membership_transition", "from", "active", "to", "revoked")).isEqualTo(1.0);
    }

    @Test
    void metrics_carry_only_closed_enum_tags() {
        CustomerId customer = newCustomer();
        Membership term = granted(customer);
        clock.set(MID);
        termination().cancelAtPeriodEnd(customer);
        termination().revoke(term.membershipId());
        assertFailure(() -> termination().revoke(MembershipId.generate()), MembershipFailure.Reason.NOT_FOUND);
        registry.getMeters().forEach(m -> m.getId().getTags().forEach(t -> {
            assertThat(t.getValue()).doesNotContain(term.membershipId().value()).doesNotContain(customer.value())
                    .doesNotContain(PLAN_ID);
            assertThat(java.util.List.of("operation", "reason", "from", "to")).contains(t.getKey());
        }));
    }

    // =============================== repository CAS guards ===============================

    @Test
    void the_cancel_CAS_refuses_a_wrong_version_a_second_request_a_stale_window_and_a_non_ACTIVE_term() {
        MembershipRepository repo = new MembershipRepository(db);
        Membership m = granted(newCustomer());

        assertThat(inTx(s -> repo.markCancelRequested(s, m.membershipId(), m.version() + 1, MID))).as("wrong version").isFalse();
        assertThat(inTx(s -> repo.markCancelRequested(s, m.membershipId(), m.version(), T0_UNTIL))).as("window ended").isFalse();
        assertThat(inTx(s -> repo.markCancelRequested(s, m.membershipId(), m.version(), T0.minusMillis(1)))).as("not started").isFalse();
        assertThat(inTx(s -> repo.markCancelRequested(s, m.membershipId(), m.version(), MID))).isTrue();
        assertThat(inTx(s -> repo.markCancelRequested(s, m.membershipId(), m.version() + 1, MID.plusSeconds(1))))
                .as("a second request can never rewrite the original").isFalse();
        assertThat(raw(m.membershipId()).get("cancelRequestedAt")).isEqualTo(Date.from(MID));

        Membership revoked = granted(newCustomer());
        inTx(s -> repo.markRevoked(s, revoked.membershipId(), revoked.version(), MID));
        assertThat(inTx(s -> repo.markCancelRequested(s, revoked.membershipId(), revoked.version() + 1, MID)))
                .as("not ACTIVE").isFalse();
    }

    @Test
    void the_revoke_CAS_refuses_a_wrong_version_a_stale_window_and_a_term_that_is_not_ACTIVE() {
        MembershipRepository repo = new MembershipRepository(db);
        Membership m = granted(newCustomer());

        assertThat(inTx(s -> repo.markRevoked(s, m.membershipId(), m.version() + 1, MID))).as("wrong version").isFalse();
        assertThat(inTx(s -> repo.markRevoked(s, m.membershipId(), m.version(), T0_UNTIL))).as("window ended").isFalse();
        assertThat(inTx(s -> repo.markRevoked(s, m.membershipId(), m.version(), T0.minusMillis(1)))).as("not started").isFalse();
        assertThat(raw(m.membershipId()).getString("status")).isEqualTo("ACTIVE");

        assertThat(inTx(s -> repo.markRevoked(s, m.membershipId(), m.version(), MID))).isTrue();
        assertThat(inTx(s -> repo.markRevoked(s, m.membershipId(), m.version() + 1, MID.plusSeconds(1))))
                .as("already REVOKED").isFalse();
        assertThat(raw(m.membershipId()).get("revokedAt")).isEqualTo(Date.from(MID));
        assertThat(raw(m.membershipId()).containsKey("openTerm")).isFalse();

        CustomerId customer = newCustomer();
        Membership old = granted(customer);
        clock.set(T0_UNTIL.plusSeconds(60));
        granted(customer); // expires `old`
        assertThat(inTx(s -> repo.markRevoked(s, old.membershipId(), old.version() + 1, MID))).as("EXPIRED").isFalse();
    }
}
