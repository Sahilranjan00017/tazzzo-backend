package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
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
 * PR-16A-1 -- the structural invariants under REAL concurrency (real Mongo, real transactions). Callers are released
 * together by a latch (no sleeps); every assertion is an invariant that must hold for ANY interleaving.
 */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipConcurrencyIT extends AbstractMembershipIT {

    private static final int THREADS = 8;
    private static final int ROUNDS = 4;

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    /** Either the granted/replayed term or the failure reason; any other throwable fails the test. */
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

    private long openRows(CustomerId customer) {
        return rows(customer).stream().filter(r -> r.containsKey("openTerm")).count();
    }

    /** A -- N concurrent DISTINCT references for one customer: exactly one open term. */
    @Test
    void concurrent_distinct_references_for_one_customer_yield_exactly_one_open_term() throws Exception {
        MembershipService svc = service();
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            List<Outcome> outcomes = raceTogether(THREADS, i -> {
                String ref = newRef();
                return () -> svc.grant(customer, PLAN_ID, 1, ref);
            });

            assertThat(outcomes.stream().filter(Outcome::succeeded)).as("exactly one winner").hasSize(1);
            assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::reason))
                    .as("every loser learns the slot is held").containsOnly(MembershipFailure.Reason.ALREADY_ACTIVE);
            assertThat(rows(customer)).hasSize(1);
            assertThat(openRows(customer)).isEqualTo(1);
        }
    }

    /** B -- N concurrent grants with the SAME reference converge on ONE durable term. */
    @Test
    void concurrent_same_reference_grants_converge_on_one_membership() throws Exception {
        MembershipService svc = service();
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            String ref = newRef();
            List<Outcome> outcomes = raceTogether(THREADS, i -> () -> svc.grant(customer, PLAN_ID, 1, ref));

            assertThat(outcomes).as("every caller succeeds (winner or replay)").allMatch(Outcome::succeeded);
            assertThat(outcomes.stream().map(o -> o.membership().membershipId()).distinct())
                    .as("all callers converge on the same Membership").hasSize(1);
            assertThat(rows(customer)).hasSize(1);
            assertThat(openRows(customer)).isEqualTo(1);
        }
    }

    /** C -- a lazy-expiry race over one stale open term leaves exactly one valid replacement. */
    @Test
    void a_lazy_expiry_race_leaves_exactly_one_replacement_and_expires_the_stale_term_once() throws Exception {
        MembershipService svc = service();
        for (int round = 0; round < ROUNDS; round++) {
            CustomerId customer = newCustomer();
            Membership stale = svc.grant(customer, PLAN_ID, 1, newRef());
            clock.set(T0_UNTIL.plusSeconds(1));

            List<Outcome> outcomes = raceTogether(THREADS, i -> {
                String ref = newRef();
                return () -> svc.grant(customer, PLAN_ID, 1, ref);
            });

            assertThat(outcomes.stream().filter(Outcome::succeeded)).as("exactly one replacement").hasSize(1);
            assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::reason))
                    .containsOnly(MembershipFailure.Reason.ALREADY_ACTIVE);
            Document expired = raw(stale.membershipId());
            assertThat(expired.getString("status")).isEqualTo("EXPIRED");
            assertThat(((Number) expired.get("version")).longValue()).as("expired exactly once").isEqualTo(2L);
            assertThat(rows(customer)).hasSize(2);
            assertThat(openRows(customer)).isEqualTo(1);
            clock.set(T0); // next round starts from the original instant
        }
    }

    /** C2 -- the same race with ONE shared reference: every caller converges on the one replacement. */
    @Test
    void a_lazy_expiry_race_with_one_shared_reference_converges_on_one_replacement() throws Exception {
        MembershipService svc = service();
        CustomerId customer = newCustomer();
        Membership stale = svc.grant(customer, PLAN_ID, 1, newRef());
        clock.set(T0_UNTIL.plusSeconds(1));
        String ref = newRef();

        List<Outcome> outcomes = raceTogether(THREADS, i -> () -> svc.grant(customer, PLAN_ID, 1, ref));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(o -> o.membership().membershipId()).distinct()).hasSize(1);
        assertThat(outcomes.get(0).membership().membershipId()).isNotEqualTo(stale.membershipId());
        assertThat(raw(stale.membershipId()).getString("status")).isEqualTo("EXPIRED");
        assertThat(rows(customer)).hasSize(2);
        assertThat(openRows(customer)).isEqualTo(1);
    }

    /** Different customers never contend: every one of them is granted. */
    @Test
    void concurrent_grants_for_different_customers_all_succeed() throws Exception {
        MembershipService svc = service();
        List<CustomerId> customers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            customers.add(newCustomer());
        }
        List<Outcome> outcomes = raceTogether(THREADS, i -> {
            String ref = newRef();
            return () -> svc.grant(customers.get(i), PLAN_ID, 1, ref);
        });
        assertThat(outcomes).allMatch(Outcome::succeeded);
        customers.forEach(c -> assertThat(openRows(c)).isEqualTo(1));
    }
}
