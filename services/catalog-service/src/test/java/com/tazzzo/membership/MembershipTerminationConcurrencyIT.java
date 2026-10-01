package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-16A-3 -- the termination commands under REAL concurrency (real Mongo transactions). A ticking clock makes every
 * attempt's "now" distinct, so a rewritten timestamp or a lost update is visible in the FINAL persisted document,
 * which is what these tests assert (not merely returned values). No sleeps; callers are released by a latch.
 */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipTerminationConcurrencyIT extends AbstractMembershipIT {

    private static final int THREADS = 8;
    private static final int ROUNDS = 4;

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    private record Outcome(Membership membership, MembershipFailure.Reason reason) {
        boolean succeeded() {
            return membership != null;
        }
    }

    private List<Outcome> raceTogether(int threads, IntFunction<Callable<Membership>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Membership> task = work.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(task.call(), null);
                    } catch (MembershipFailure e) {
                        return new Outcome(null, e.reason());
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                try {
                    outcomes.add(f.get(120, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("unexpected non-Membership failure: " + e.getCause(), e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private Clock tickingFrom(Instant base) {
        return new TickingClock(base);
    }

    private MembershipTerminationService termination(Clock c) {
        return new MembershipTerminationService(new MembershipRepository(db), new MembershipObservability(registry), c,
                new Tx(client));
    }

    private MembershipService grants(Clock c) {
        return new MembershipService(new MembershipRepository(db), planSource(PLAN),
                new MembershipObservability(registry), c, new Tx(client));
    }

    private static long versionOf(Document d) {
        return ((Number) d.get("version")).longValue();
    }

    private long openRows(CustomerId customer) {
        return rows(customer).stream().filter(r -> r.containsKey("openTerm")).count();
    }

    /** cancel vs cancel: ONE effective cancellation; the loser re-reads and returns the idempotent result. */
    @Test
    void concurrent_cancels_yield_one_effective_cancellation_with_one_stable_timestamp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            Membership term = service().grant(customer, PLAN_ID, 1, newRef());
            MembershipTerminationService svc = termination(tickingFrom(T0.plusSeconds(3600)));

            List<Outcome> outcomes = raceTogether(THREADS, i -> () -> svc.cancelAtPeriodEnd(customer));

            assertThat(outcomes).as("every caller succeeds").allMatch(Outcome::succeeded);
            Document finalDoc = raw(term.membershipId());
            assertThat(versionOf(finalDoc)).as("exactly one mutation").isEqualTo(2L);
            Date winner = finalDoc.getDate("cancelRequestedAt");
            assertThat(outcomes.stream().map(o -> o.membership().cancelRequestedAt()).distinct())
                    .as("every caller reports the ONE winning timestamp").containsExactly(winner.toInstant());
            assertThat(finalDoc.getString("status")).isEqualTo("ACTIVE");
            assertThat(finalDoc.get("openTerm")).isEqualTo(Boolean.TRUE);
            assertThat(finalDoc.get("validUntil")).isEqualTo(Date.from(T0_UNTIL));
            assertThat(finalDoc.get("updatedAt")).isEqualTo(winner);
            MembershipRepository.toMembership(finalDoc); // still strictly valid
        }
    }

    /** revoke vs revoke: ONE mutation; the other callers resolve idempotently; first revokedAt stays. */
    @Test
    void concurrent_revokes_yield_one_effective_revoke_with_one_stable_timestamp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            Membership term = service().grant(customer, PLAN_ID, 1, newRef());
            MembershipTerminationService svc = termination(tickingFrom(T0.plusSeconds(3600)));

            List<Outcome> outcomes = raceTogether(THREADS, i -> () -> svc.revoke(term.membershipId()));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            Document finalDoc = raw(term.membershipId());
            assertThat(versionOf(finalDoc)).as("exactly one mutation").isEqualTo(2L);
            Date winner = finalDoc.getDate("revokedAt");
            assertThat(outcomes.stream().map(o -> o.membership().revokedAt()).distinct())
                    .containsExactly(winner.toInstant());
            assertThat(finalDoc.getString("status")).isEqualTo("REVOKED");
            assertThat(finalDoc.containsKey("openTerm")).isFalse();
            assertThat(finalDoc.get("updatedAt")).isEqualTo(winner);
            assertThat(openRows(customer)).isZero();
        }
    }

    /** cancel vs revoke: never a lost update; REVOKED is the only terminal outcome; the marker never returns. */
    @Test
    void concurrent_cancel_and_revoke_always_end_REVOKED_without_a_marker_and_with_valid_history() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            Membership term = service().grant(customer, PLAN_ID, 1, newRef());
            MembershipTerminationService svc = termination(tickingFrom(T0.plusSeconds(3600)));

            List<Outcome> outcomes = raceTogether(THREADS, i -> i % 2 == 0
                    ? () -> svc.cancelAtPeriodEnd(customer) : () -> svc.revoke(term.membershipId()));

            for (int i = 0; i < outcomes.size(); i++) {
                Outcome o = outcomes.get(i);
                if (i % 2 == 1) {
                    assertThat(o.succeeded()).as("a revoke always resolves (win or idempotent)").isTrue();
                } else {
                    // cancel won first -> success; revoke committed first -> the customer has no current term
                    assertThat(o.succeeded() || o.reason() == MembershipFailure.Reason.NOT_FOUND)
                            .as("cancel resolves to success or NOT_FOUND, never anything else: " + o.reason()).isTrue();
                }
            }
            Document finalDoc = raw(term.membershipId());
            assertThat(finalDoc.getString("status")).isEqualTo("REVOKED");
            assertThat(finalDoc.containsKey("openTerm")).as("never recreated after revoke").isFalse();
            assertThat(finalDoc.get("revokedAt")).isNotNull();
            boolean cancelWon = finalDoc.containsKey("cancelRequestedAt");
            assertThat(versionOf(finalDoc)).as("cancel (optional) + revoke, each exactly once")
                    .isEqualTo(cancelWon ? 3L : 2L);
            Membership reconstructed = MembershipRepository.toMembership(finalDoc); // lifecycle invariants hold
            assertThat(reconstructed.isEntitlingAt(T0.plusSeconds(3601))).isFalse();
            assertThat(openRows(customer)).isZero();
            if (cancelWon) {
                assertThat(outcomes.stream().filter(o -> o.reason() == null).anyMatch(o -> o.membership().cancelRequestedAt() != null))
                        .isTrue();
            }
        }
    }

    /** revoke vs grant for one customer: the open-term invariant holds in every interleaving. */
    @Test
    void a_revoke_racing_a_grant_never_produces_two_open_terms() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            Membership term = service().grant(customer, PLAN_ID, 1, newRef());
            Clock c = tickingFrom(T0.plusSeconds(3600));
            MembershipTerminationService svc = termination(c);
            MembershipService grants = grants(c);
            String newRef = newRef();

            List<Outcome> outcomes = raceTogether(2, i -> i == 0
                    ? () -> svc.revoke(term.membershipId()) : () -> grants.grant(customer, PLAN_ID, 1, newRef));

            assertThat(outcomes.get(0).succeeded()).as("the revoke resolves").isTrue();
            Outcome grantOutcome = outcomes.get(1);
            assertThat(grantOutcome.succeeded() || grantOutcome.reason() == MembershipFailure.Reason.ALREADY_ACTIVE)
                    .as("the grant either lands after the slot was freed or sees it held: " + grantOutcome.reason()).isTrue();
            assertThat(raw(term.membershipId()).getString("status")).isEqualTo("REVOKED");
            assertThat(openRows(customer)).as("at most ONE open term, exactly one iff the grant landed")
                    .isEqualTo(grantOutcome.succeeded() ? 1 : 0);
            rows(customer).forEach(MembershipRepository::toMembership); // every row strictly valid
        }
    }
}
