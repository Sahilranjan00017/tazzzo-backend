package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-16A-1 -- duplicate-key (code 11000) recovery, proved from DURABLE rows only. A scripted repository makes the
 * in-transaction reads "miss" a row that really exists, so the insert genuinely violates a real unique index and the
 * recovery path runs against real Mongo -- deterministically, no sleeps, no exception-text/index-name inspection.
 */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipDuplicateKeyRecoveryIT extends AbstractMembershipIT {

    private static final int ALWAYS = -1;

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    private static void assertFailure(Runnable call, MembershipFailure.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MembershipFailure.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    /** D -- the loser of a grant-reference race replays the winner. */
    @Test
    void a_grant_reference_winner_is_replayed() {
        CustomerId customer = newCustomer();
        String ref = newRef();
        Membership winner = service().grant(customer, PLAN_ID, 1, ref);

        ScriptedRepository repo = new ScriptedRepository(db);
        repo.hideNonSessionRefLookups.set(1);      // the pre-transaction replay check misses
        repo.hideSessionRefLookups.set(ALWAYS);    // so does the in-session one
        repo.hideSessionOpenLookups.set(ALWAYS);   // ... and the open-term read: the insert really collides
        CountingTx tx = new CountingTx(client);
        registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        Membership resolved = service(tx, repo, planSource(PLAN)).grant(customer, PLAN_ID, 1, ref);

        assertThat(resolved).isEqualTo(winner);
        assertThat(rows(customer)).hasSize(1);
        assertThat(tx.calls.get()).as("a replay needs no retry").isEqualTo(1);
        assertThat(count("membership_operation_success", "operation", "grant")).isEqualTo(1.0);
        assertThat(count("membership_failure")).isZero();
        assertThat(count("membership_transition")).isZero();
    }

    @Test
    void a_grant_reference_winner_with_different_input_is_GRANT_REF_CONFLICT() {
        CustomerId winnerCustomer = newCustomer();
        String ref = newRef();
        service().grant(winnerCustomer, PLAN_ID, 1, ref);

        CustomerId other = newCustomer();
        ScriptedRepository repo = new ScriptedRepository(db);
        repo.hideNonSessionRefLookups.set(1);
        repo.hideSessionRefLookups.set(ALWAYS);
        MembershipService svc = service(new Tx(client), repo, planSource(PLAN));

        assertFailure(() -> svc.grant(other, PLAN_ID, 1, ref), MembershipFailure.Reason.GRANT_REF_CONFLICT);
        assertThat(rows(other)).as("the loser's insert was rolled back").isEmpty();
    }

    /** E -- the loser of an open-slot race learns the slot is held. */
    @Test
    void an_open_slot_winner_is_ALREADY_ACTIVE() {
        CustomerId customer = newCustomer();
        service().grant(customer, PLAN_ID, 1, newRef());

        ScriptedRepository repo = new ScriptedRepository(db);
        repo.hideSessionOpenLookups.set(ALWAYS); // the in-session read misses, so the insert collides on the slot
        MembershipService svc = service(new Tx(client), repo, planSource(PLAN));

        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.ALREADY_ACTIVE);
        assertThat(rows(customer)).hasSize(1);
        assertThat(count("membership_failure", "operation", "grant", "reason", "already_active")).isEqualTo(1.0);
    }

    /** F -- a stale open row is NOT reported as ALREADY_ACTIVE: one bounded whole-grant retry expires it and succeeds. */
    @Test
    void a_stale_open_row_triggers_exactly_one_bounded_retry_which_succeeds() {
        CustomerId customer = newCustomer();
        Membership stale = service().grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0_UNTIL.plusSeconds(3600));
        registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        ScriptedRepository repo = new ScriptedRepository(db);
        repo.hideSessionOpenLookups.set(1); // attempt 1 misses the stale row and collides on the slot
        CountingTx tx = new CountingTx(client);

        Membership replacement = service(tx, repo, planSource(PLAN)).grant(customer, PLAN_ID, 1, newRef());

        assertThat(tx.calls.get()).as("original attempt + exactly one retry").isEqualTo(2);
        assertThat(raw(stale.membershipId()).getString("status")).isEqualTo("EXPIRED");
        assertThat(raw(replacement.membershipId()).get("openTerm")).isEqualTo(Boolean.TRUE);
        assertThat(rows(customer)).hasSize(2);
        assertThat(rows(customer).stream().filter(r -> r.containsKey("openTerm"))).hasSize(1);
        assertThat(count("membership_operation_success", "operation", "grant")).isEqualTo(1.0);
        assertThat(count("membership_transition", "from", "active", "to", "expired")).isEqualTo(1.0);
        assertThat(count("membership_failure")).isZero();
    }

    /** G -- a duplicate key that never gains durable proof: one retry, then INTEGRITY_FAILURE (never a loop). */
    @Test
    void a_duplicate_key_with_no_durable_proof_is_one_retry_then_INTEGRITY_FAILURE() {
        CustomerId customer = newCustomer();
        DuplicateKeyTx tx = new DuplicateKeyTx(client, "E11000 duplicate key error");
        MembershipService svc = service(tx, new MembershipRepository(db), planSource(PLAN));

        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.INTEGRITY_FAILURE);

        assertThat(tx.calls.get()).as("bounded: original + one retry, no more").isEqualTo(2);
        assertThat(rows(customer)).isEmpty();
        assertThat(count("membership_failure", "operation", "grant", "reason", "integrity_failure")).isEqualTo(1.0);
        assertThat(count("membership_operation_success")).isZero();
    }

    /** H -- the server's message text and index name never influence recovery. */
    @Test
    void nonsense_or_misleading_duplicate_key_text_does_not_change_the_outcome() {
        CustomerId customer = newCustomer();
        String winnerRef = newRef();
        Membership winner = service().grant(customer, PLAN_ID, 1, winnerRef);

        // nonsense text, a durable OPEN-SLOT winner => ALREADY_ACTIVE
        MembershipService nonsense = service(new DuplicateKeyTx(client, "¯\\_(ツ)_/¯ totally unrelated"),
                new MembershipRepository(db), planSource(PLAN));
        assertFailure(() -> nonsense.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.ALREADY_ACTIVE);

        // text that NAMES the grant-reference index although the durable truth is the open slot => still ALREADY_ACTIVE
        MembershipService misleading = service(new DuplicateKeyTx(client,
                "E11000 duplicate key error index: membership_one_per_grant_reference dup key"),
                new MembershipRepository(db), planSource(PLAN));
        assertFailure(() -> misleading.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.ALREADY_ACTIVE);

        // text that NAMES the open-term index although the durable truth is a reference winner => replay
        ScriptedRepository repo = new ScriptedRepository(db);
        repo.hideNonSessionRefLookups.set(1);
        MembershipService namesWrongIndex = service(new DuplicateKeyTx(client,
                "E11000 duplicate key error index: membership_one_open_per_customer dup key"), repo, planSource(PLAN));
        assertThat(namesWrongIndex.grant(customer, PLAN_ID, 1, winnerRef)).isEqualTo(winner);
        assertThat(rows(customer)).hasSize(1);
    }

    /** A recovery read that itself fails is a typed UNAVAILABLE. */
    @Test
    void a_failing_recovery_read_is_UNAVAILABLE() {
        CustomerId customer = newCustomer();
        ScriptedRepository repo = new ScriptedRepository(db);
        repo.failNonSessionReadsAfterFirst = true; // call 1 (pre-transaction replay check) is real; recovery's fails
        MembershipService svc = service(new DuplicateKeyTx(client, "dup"), repo, planSource(PLAN));

        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.UNAVAILABLE);
        assertThat(count("membership_failure", "operation", "grant", "reason", "unavailable")).isEqualTo(1.0);
    }

    /** A non-11000 write error is a plain outage, never mistaken for a duplicate. */
    @Test
    void a_non_duplicate_write_error_is_UNAVAILABLE_and_runs_no_recovery() {
        CustomerId customer = newCustomer();
        Tx tx = new Tx(client) {
            @Override
            public <T> T call(java.util.function.Function<com.mongodb.client.ClientSession, T> body) {
                throw new com.mongodb.MongoWriteException(new com.mongodb.WriteError(121, "document failed validation",
                        new org.bson.BsonDocument()), new com.mongodb.ServerAddress());
            }
        };
        ScriptedRepository repo = new ScriptedRepository(db);
        MembershipService svc = service(tx, repo, planSource(PLAN));

        assertFailure(() -> svc.grant(customer, PLAN_ID, 1, newRef()), MembershipFailure.Reason.UNAVAILABLE);
        assertThat(repo.nonSessionRefCalls.get()).as("only the pre-transaction replay read; no recovery read").isEqualTo(1);
    }
}
