package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Money;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-16A-1 -- {@link MembershipService#grant}: grant, replay, conflict, lazy expiry, atomicity, retry safety,
 * outage and the closed failure vocabulary, against real Mongo (Testcontainers replica set).
 */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipServiceIT extends AbstractMembershipIT {

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    private static void assertFailure(Runnable call, MembershipFailure.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MembershipFailure.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    // ---------- the grant ----------

    @Test
    void a_valid_grant_persists_an_ACTIVE_open_term_with_the_approved_shape() {
        CustomerId customer = newCustomer();
        String ref = newRef();

        Membership m = service().grant(customer, PLAN_ID, 1, ref);

        assertThat(m.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(m.version()).isEqualTo(1L);
        assertThat(m.periodCount()).isEqualTo(1L);
        assertThat(m.grantReference()).isEqualTo(new MembershipGrantReference(GrantSource.INTERNAL_GRANT, ref));
        assertThat(m.billingZoneId()).isEqualTo("Asia/Kolkata");
        assertThat(m.planPrice()).isEqualTo(Money.ofInrPaise(9900));
        assertThat(m.validFrom()).isEqualTo(T0);
        assertThat(m.validUntil()).isEqualTo(T0_UNTIL);
        assertThat(m.createdAt()).isEqualTo(T0);
        assertThat(m.updatedAt()).isEqualTo(T0);

        Document d = raw(m.membershipId());
        assertThat(d.get("openTerm")).isEqualTo(Boolean.TRUE);
        assertThat(d.getString("status")).isEqualTo("ACTIVE");
        assertThat(d.getString("grantSource")).isEqualTo("INTERNAL_GRANT");
        assertThat(d.getString("grantRef")).isEqualTo(ref);
        assertThat(d.getString("billingZoneId")).isEqualTo("Asia/Kolkata");
        assertThat(d.getString("planCurrency")).isEqualTo("INR");
        assertThat(((Number) d.get("planPricePaise")).longValue()).isEqualTo(9900L);
        assertThat(d.get("validFrom")).isEqualTo(Date.from(T0));
        assertThat(d.get("validUntil")).isEqualTo(Date.from(T0_UNTIL));
        assertThat(MembershipRepository.toMembership(d)).isEqualTo(m);

        assertThat(count("membership_operation_success", "operation", "grant")).isEqualTo(1.0);
        assertThat(count("membership_failure")).isZero();
        assertThat(count("membership_transition")).isZero();
    }

    @Test
    void the_grant_instant_is_the_injected_clock_truncated_to_milliseconds() {
        clock.set(Instant.parse("2027-02-28T20:00:00.123456789Z"));
        Membership m = service().grant(newCustomer(), PLAN_ID, 1, newRef());
        assertThat(m.validFrom()).isEqualTo(Instant.parse("2027-02-28T20:00:00.123Z"));
        assertThat(m.validUntil()).isEqualTo(Instant.parse("2027-03-31T20:00:00.123Z"));
    }

    // ---------- replay / conflict ----------

    @Test
    void the_same_reference_replays_the_original_membership_unchanged() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        MembershipService svc = service();

        Membership first = svc.grant(customer, PLAN_ID, 1, ref);
        clock.set(T0.plusSeconds(60));
        Membership replay = svc.grant(customer, PLAN_ID, 1, ref);

        assertThat(replay).isEqualTo(first);
        assertThat(rows(customer)).hasSize(1);
        assertThat(count("membership_operation_success", "operation", "grant")).isEqualTo(2.0);
    }

    @Test
    void a_replay_still_succeeds_after_the_plan_was_retired() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        Membership first = service().grant(customer, PLAN_ID, 1, ref);

        MembershipPlan retired = new MembershipPlan(PLAN_ID, 1, Money.ofInrPaise(9900), 1,
                Instant.parse("2026-01-01T00:00:00Z"), T0.plusSeconds(3600));
        clock.set(T0.plusSeconds(10 * 3600L)); // well past the retirement
        MembershipService afterRetirement = service(new Tx(client), new MembershipRepository(db), planSource(retired));

        assertThat(afterRetirement.grant(customer, PLAN_ID, 1, ref)).isEqualTo(first);
        assertFailure(() -> afterRetirement.grant(newCustomer(), PLAN_ID, 1, newRef()),
                MembershipFailure.Reason.PLAN_NOT_ACTIVE);
    }

    @Test
    void a_replay_returns_the_original_after_its_window_ended_and_after_it_was_lazily_expired() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        MembershipService svc = service();
        Membership first = svc.grant(customer, PLAN_ID, 1, ref);

        clock.set(T0_UNTIL.plusSeconds(1)); // window over, persisted row still ACTIVE (stale)
        assertThat(svc.grant(customer, PLAN_ID, 1, ref)).isEqualTo(first);

        svc.grant(customer, PLAN_ID, 1, newRef()); // lazily expires the stale term, opens a new one
        Membership replayAfterExpiry = svc.grant(customer, PLAN_ID, 1, ref);
        assertThat(replayAfterExpiry.membershipId()).isEqualTo(first.membershipId());
        assertThat(replayAfterExpiry.status()).isEqualTo(MembershipStatus.EXPIRED);
        assertThat(rows(customer)).hasSize(2);
    }

    @Test
    void the_same_reference_with_a_different_customer_plan_or_version_is_GRANT_REF_CONFLICT() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        MembershipService svc = service();
        svc.grant(customer, PLAN_ID, 1, ref);

        assertFailure(() -> svc.grant(newCustomer(), PLAN_ID, 1, ref), MembershipFailure.Reason.GRANT_REF_CONFLICT);
        assertFailure(() -> svc.grant(customer, "OTHER_PLAN", 1, ref), MembershipFailure.Reason.GRANT_REF_CONFLICT);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 2, ref), MembershipFailure.Reason.GRANT_REF_CONFLICT);
        assertThat(rows(customer)).hasSize(1);
        assertThat(count("membership_failure", "operation", "grant", "reason", "grant_ref_conflict")).isEqualTo(3.0);
    }

    // ---------- one open term / lazy expiry ----------

    @Test
    void a_new_grant_while_the_term_is_open_is_ALREADY_ACTIVE_up_to_the_exact_expiry_instant() {
        CustomerId customer = newCustomer();
        MembershipService svc = service();
        Membership first = svc.grant(customer, PLAN_ID, 1, newRef());

        clock.set(T0_UNTIL.minusMillis(1));
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.ALREADY_ACTIVE);
        assertThat(rows(customer)).hasSize(1);

        clock.set(T0_UNTIL); // half-open window: the end instant itself is already outside it
        Membership second = svc.grant(customer, PLAN_ID, 1, newRef());
        assertThat(second.membershipId()).isNotEqualTo(first.membershipId());
        assertThat(second.validFrom()).isEqualTo(T0_UNTIL);
    }

    @Test
    void a_stale_ACTIVE_term_is_lazily_expired_and_replaced_in_one_transaction() {
        CustomerId customer = newCustomer();
        MembershipService svc = service();
        Membership stale = svc.grant(customer, PLAN_ID, 1, newRef());
        Document before = raw(stale.membershipId());
        assertThat(before.getString("status")).isEqualTo("ACTIVE"); // persisted ACTIVE ...

        Instant later = T0_UNTIL.plusSeconds(86_400);
        clock.set(later);
        Membership replacement = svc.grant(customer, PLAN_ID, 1, newRef());

        Document expired = raw(stale.membershipId());
        assertThat(expired.getString("status")).isEqualTo("EXPIRED");
        assertThat(expired.containsKey("openTerm")).as("openTerm is UNSET, never false/null").isFalse();
        assertThat(((Number) expired.get("version")).longValue()).isEqualTo(2L);
        assertThat(expired.get("updatedAt")).isEqualTo(Date.from(later));
        assertThat(expired.get("validUntil")).isEqualTo(Date.from(T0_UNTIL)); // the window itself is untouched
        assertThat(MembershipRepository.toMembership(expired).status()).isEqualTo(MembershipStatus.EXPIRED);

        Document open = raw(replacement.membershipId());
        assertThat(open.get("openTerm")).isEqualTo(Boolean.TRUE);
        assertThat(replacement.validFrom()).isEqualTo(later);
        assertThat(rows(customer)).hasSize(2);
        assertThat(rows(customer).stream().filter(r -> r.containsKey("openTerm"))).hasSize(1);

        assertThat(count("membership_transition", "from", "active", "to", "expired")).isEqualTo(1.0);
        assertThat(count("membership_operation_success", "operation", "grant")).isEqualTo(2.0);
    }

    @Test
    void if_the_replacement_insert_fails_the_stale_term_is_restored_by_the_rollback() {
        CustomerId customer = newCustomer();
        Membership stale = service().grant(customer, PLAN_ID, 1, newRef());
        Document before = raw(stale.membershipId());

        clock.set(T0_UNTIL.plusSeconds(60));
        ScriptedRepository repo = new ScriptedRepository(db);
        repo.failInsert = true;
        MembershipService failing = service(new Tx(client), repo, planSource(PLAN));

        assertFailure(() -> failing.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.UNAVAILABLE);

        assertThat(raw(stale.membershipId())).as("expiry rolled back with the failed insert").isEqualTo(before);
        assertThat(rows(customer)).hasSize(1);
        assertThat(count("membership_transition")).as("no transition metric for a rolled-back expiry").isZero();
        assertThat(count("membership_failure", "operation", "grant", "reason", "unavailable")).isEqualTo(1.0);
    }

    @Test
    void a_CAS_miss_after_an_authoritative_same_session_read_is_INTEGRITY_FAILURE() {
        CustomerId customer = newCustomer();
        Membership stale = service().grant(customer, PLAN_ID, 1, newRef());
        Document before = raw(stale.membershipId());

        clock.set(T0_UNTIL.plusSeconds(60));
        ScriptedRepository repo = new ScriptedRepository(db);
        repo.expireCasMisses = true;
        MembershipService svc = service(new Tx(client), repo, planSource(PLAN));

        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.INTEGRITY_FAILURE);
        assertThat(raw(stale.membershipId())).isEqualTo(before);
        assertThat(rows(customer)).hasSize(1);
    }

    @Test
    void the_expiry_cas_can_never_persist_EXPIRED_before_the_window_ends() {
        CustomerId customer = newCustomer();
        Membership m = service().grant(customer, PLAN_ID, 1, newRef());
        MembershipRepository repo = new MembershipRepository(db);

        boolean early = new Tx(client).call(session ->
                repo.expireIfDue(session, m.membershipId(), m.version(), T0_UNTIL.minusMillis(1)));
        boolean wrongVersion = new Tx(client).call(session ->
                repo.expireIfDue(session, m.membershipId(), m.version() + 1, T0_UNTIL));
        assertThat(early).as("validUntil <= now is part of the CAS filter").isFalse();
        assertThat(wrongVersion).as("expected version is part of the CAS filter").isFalse();
        assertThat(raw(m.membershipId()).getString("status")).isEqualTo("ACTIVE");

        boolean due = new Tx(client).call(session -> repo.expireIfDue(session, m.membershipId(), m.version(), T0_UNTIL));
        assertThat(due).isTrue();
        boolean again = new Tx(client).call(session -> repo.expireIfDue(session, m.membershipId(), m.version(), T0_UNTIL));
        assertThat(again).as("already terminal: the status guard refuses a second transition").isFalse();
    }

    // ---------- validation / plan ----------

    @Test
    void malformed_input_is_INVALID_REQUEST_and_each_failure_is_counted_once() {
        MembershipService svc = service();
        CustomerId customer = newCustomer();
        assertFailure(() -> svc.grant(null, PLAN_ID, 1, newRef()), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, null, 1, newRef()), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, "bad plan", 1, newRef()), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 0, newRef()), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, null), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, ""), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, "has space"), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, "x".repeat(129)), MembershipFailure.Reason.INVALID_REQUEST);
        assertThat(count("membership_failure", "operation", "grant", "reason", "invalid_request")).isEqualTo(8.0);
        assertThat(rows(customer)).isEmpty();
    }

    @Test
    void a_malformed_customer_id_cannot_even_be_constructed() {
        assertThatThrownBy(() -> new CustomerId("not-a-customer")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_unknown_plan_or_version_is_INVALID_REQUEST() {
        MembershipService svc = service();
        assertFailure(() -> svc.grant(newCustomer(), "NO_SUCH_PLAN", 1, newRef()), MembershipFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.grant(newCustomer(), PLAN_ID, 99, newRef()), MembershipFailure.Reason.INVALID_REQUEST);
    }

    @Test
    void a_plan_not_yet_effective_or_already_retired_is_PLAN_NOT_ACTIVE_with_an_exclusive_end() {
        Instant start = Instant.parse("2027-06-01T00:00:00Z");
        Instant end = Instant.parse("2027-07-01T00:00:00Z");
        MembershipPlan windowed = new MembershipPlan(PLAN_ID, 1, Money.ofInrPaise(9900), 1, start, end);
        MembershipService svc = service(new Tx(client), new MembershipRepository(db), planSource(windowed));

        clock.set(start.minusMillis(1));
        assertFailure(() -> svc.grant(newCustomer(), PLAN_ID, 1, newRef()), MembershipFailure.Reason.PLAN_NOT_ACTIVE);
        clock.set(start);
        assertThat(svc.grant(newCustomer(), PLAN_ID, 1, newRef()).validFrom()).isEqualTo(start);
        clock.set(end.minusMillis(1));
        assertThat(svc.grant(newCustomer(), PLAN_ID, 1, newRef()).status()).isEqualTo(MembershipStatus.ACTIVE);
        clock.set(end);
        assertFailure(() -> svc.grant(newCustomer(), PLAN_ID, 1, newRef()), MembershipFailure.Reason.PLAN_NOT_ACTIVE);
    }

    @Test
    void the_term_snapshots_its_plan_so_a_later_plan_version_never_reinterprets_it() {
        CustomerId customer = newCustomer();
        Membership v1 = service().grant(customer, PLAN_ID, 1, newRef());

        MembershipPlan v1Closed = new MembershipPlan(PLAN_ID, 1, Money.ofInrPaise(9900), 1,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-02-01T00:00:00Z"));
        MembershipPlan v2 = new MembershipPlan(PLAN_ID, 2, Money.ofInrPaise(14900), 3,
                Instant.parse("2027-02-01T00:00:00Z"), null);
        MembershipService later = service(new Tx(client), new MembershipRepository(db), planSource(v1Closed, v2));

        Membership reread = MembershipRepository.toMembership(raw(v1.membershipId()));
        assertThat(reread.planVersion()).isEqualTo(1);
        assertThat(reread.planPrice().paise()).isEqualTo(9900L);
        assertThat(reread.planPeriodMonths()).isEqualTo(1);
        assertThat(reread.validUntil()).isEqualTo(T0_UNTIL);

        clock.set(T0_UNTIL.plusSeconds(1));
        Membership onV2 = later.grant(customer, PLAN_ID, 2, newRef());
        assertThat(onV2.planPrice().paise()).isEqualTo(14900L);
        assertThat(onV2.planPeriodMonths()).isEqualTo(3);
        assertThat(onV2.validUntil()).isEqualTo(Instant.parse("2027-05-28T04:30:01Z"));
    }

    // ---------- outage ----------

    @Test
    void a_datastore_outage_is_a_typed_UNAVAILABLE_never_a_raw_mongo_exception() {
        MembershipService svc = service(new OutageTx(client), new MembershipRepository(db), planSource(PLAN));
        CustomerId customer = newCustomer();
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.UNAVAILABLE);
        assertThat(count("membership_failure", "operation", "grant", "reason", "unavailable")).isEqualTo(1.0);
        assertThat(count("membership_operation_success")).isZero();
        assertThat(rows(customer)).isEmpty();
    }

    @Test
    void a_failing_pre_transaction_replay_read_is_UNAVAILABLE() {
        MembershipRepository broken = new MembershipRepository(db) {
            @Override
            public java.util.Optional<Membership> findByGrantReference(MembershipGrantReference reference) {
                throw new com.mongodb.MongoException("simulated read failure");
            }
        };
        MembershipService svc = service(new Tx(client), broken, planSource(PLAN));
        assertFailure(() -> svc.grant(newCustomer(), PLAN_ID, 1, newRef()), MembershipFailure.Reason.UNAVAILABLE);
    }

    // ---------- retry safety ----------

    @Test
    void a_retried_transaction_creates_exactly_one_term_with_one_identity_and_one_metric() {
        CustomerId customer = newCustomer();
        RetryInjectingTx retrying = new RetryInjectingTx(client);
        MembershipService svc = service(retrying, new MembershipRepository(db), planSource(PLAN));

        retrying.arm(1); // attempt 1 applies its writes then "fails transiently": the driver rolls back and re-runs
        Membership m = svc.grant(customer, PLAN_ID, 1, newRef());

        assertThat(retrying.attempts()).isEqualTo(2);
        assertThat(retrying.attemptResults()).hasSize(2);
        assertThat(retrying.attemptResults().get(0)).as("same logical identity on every attempt")
                .isEqualTo(retrying.attemptResults().get(1));
        assertThat(rows(customer)).hasSize(1);
        assertThat(rows(customer).get(0).getString("_id")).isEqualTo(m.membershipId().value());
        assertThat(count("membership_operation_success", "operation", "grant")).as("no per-attempt metric").isEqualTo(1.0);
        assertThat(count("membership_failure")).isZero();
    }

    @Test
    void a_retried_lazy_expiry_expires_the_stale_term_once_and_records_one_transition() {
        CustomerId customer = newCustomer();
        Membership stale = service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0_UNTIL.plusSeconds(5));

        RetryInjectingTx retrying = new RetryInjectingTx(client);
        MembershipService svc = service(retrying, new MembershipRepository(db), planSource(PLAN));
        retrying.arm(2);
        Membership replacement = svc.grant(customer, PLAN_ID, 1, newRef());

        assertThat(retrying.attempts()).isEqualTo(3);
        Document expired = raw(stale.membershipId());
        assertThat(expired.getString("status")).isEqualTo("EXPIRED");
        assertThat(((Number) expired.get("version")).longValue()).as("expired exactly once").isEqualTo(2L);
        assertThat(rows(customer)).hasSize(2);
        assertThat(raw(replacement.membershipId()).get("openTerm")).isEqualTo(Boolean.TRUE);
        assertThat(count("membership_transition", "from", "active", "to", "expired")).isEqualTo(1.0);
        assertThat(count("membership_operation_success", "operation", "grant")).isEqualTo(2.0);
    }

    @Test
    void metrics_use_only_closed_enum_tags_and_never_an_id_or_reference() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        MembershipService svc = service();
        Membership m = svc.grant(customer, PLAN_ID, 1, ref);
        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.ALREADY_ACTIVE);
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getValue()).doesNotContain(m.membershipId().value()).doesNotContain(customer.value())
                    .doesNotContain(ref).doesNotContain(PLAN_ID);
            assertThat(List.of("operation", "reason", "from", "to")).contains(tag.getKey());
        }));
    }
}
