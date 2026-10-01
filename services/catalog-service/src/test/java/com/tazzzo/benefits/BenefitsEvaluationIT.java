package com.tazzzo.benefits;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Money;
import com.tazzzo.membership.Membership;
import com.tazzzo.membership.MembershipEntitlement;
import com.tazzzo.membership.MembershipEntitlementPort;
import com.tazzzo.membership.MembershipService;
import com.tazzzo.membership.MembershipTerminationService;
import com.tazzzo.membership.TransactionalMembershipEntitlementPort;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.tazzzo.benefits.BenefitRuleTest.PLAN;
import static com.tazzzo.benefits.BenefitRuleTest.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Benefits evaluation against the REAL Membership beans and real Mongo: the entitlement is whatever Membership
 * proves (grant, cancel-at-period-end, revoke), Benefits never inspects lifecycle state, and the transactional
 * evaluation participates in the caller's own transaction.
 */
@SpringBootTest(classes = CatalogApplication.class)
class BenefitsEvaluationIT extends AbstractMongoIT {

    // TEST FIXTURES only (never launch policy): 5% off a subtotal of at least 50,000 paise.
    private static final long MIN = 50_000;
    private static final String MEMBERSHIPS = "memberships";

    @Autowired MembershipService memberships;
    @Autowired MembershipTerminationService termination;
    @Autowired MembershipEntitlementPort membershipPort;
    @Autowired TransactionalMembershipEntitlementPort transactionalMembershipPort;
    @Autowired BenefitsEvaluationPort wiredStandalone;
    @Autowired TransactionalBenefitsEvaluationPort wiredTransactional;
    @Autowired BenefitRuleSource wiredRules;
    @Autowired BenefitsObservability observability;
    @Autowired MeterRegistry springRegistry;

    private BenefitsEvaluationService standalone(BenefitRule... rules) {
        return new BenefitsEvaluationService(membershipPort, new ConfigBackedBenefitRuleSource(List.of(rules)),
                observability);
    }

    private BenefitsTransactionalEvaluator transactional(TransactionalMembershipEntitlementPort port,
                                                         BenefitRule... rules) {
        return new BenefitsTransactionalEvaluator(port, new ConfigBackedBenefitRuleSource(List.of(rules)));
    }

    private Membership grant(CustomerId customer) {
        return memberships.grant(customer, PLAN, 1, "REF-" + UUID.randomUUID());
    }

    private static Money inr(long paise) {
        return Money.ofInrPaise(paise);
    }

    private List<Document> membershipRows(CustomerId customer) {
        return db.getCollection(MEMBERSHIPS).find(Filters.eq("customerId", customer.value()))
                .into(new java.util.ArrayList<>());
    }

    private java.util.Set<String> benefitsAndMembershipMeters() {
        return springRegistry.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().startsWith("benefits") || id.getName().startsWith("membership"))
                .map(id -> id + "=" + springRegistry.get(id.getName()).tags(id.getTags()).counter().count())
                .collect(Collectors.toSet());
    }

    // ---------- wiring / production default ----------

    @Test
    void the_production_default_configures_no_rule_so_an_entitled_customer_gets_no_invented_discount() {
        CustomerId customer = CustomerId.generate();
        grant(customer);

        assertThat(wiredRules.find(PLAN, 1)).as("no launch rule is configured").isEmpty();
        BenefitEvaluation result = wiredStandalone.evaluate(customer, inr(10_000_000));

        assertThat(result).isEqualTo(new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE));
        assertThat(wiredStandalone).isInstanceOf(BenefitsEvaluationService.class);
        assertThat(wiredTransactional).isInstanceOf(BenefitsTransactionalEvaluator.class);
    }

    // ---------- standalone over real Membership state ----------

    @Test
    void a_customer_without_a_membership_has_no_benefit() {
        assertThat(standalone(rule(1, MIN, 500)).evaluate(CustomerId.generate(), inr(MIN * 4)))
                .isEqualTo(new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
    }

    @Test
    void a_granted_term_applies_the_exact_version_rule_to_the_subtotal_and_mutates_nothing() {
        CustomerId customer = CustomerId.generate();
        Membership term = grant(customer);
        List<Document> before = membershipRows(customer);

        BenefitEvaluation.Applied applied = (BenefitEvaluation.Applied)
                standalone(rule(1, MIN, 500), rule(2, MIN, 9_000)).evaluate(customer, inr(99_999));

        assertThat(applied.membershipId()).isEqualTo(term.membershipId());
        assertThat(applied.planId()).isEqualTo(PLAN);
        assertThat(applied.planVersion()).isEqualTo(1);
        assertThat(applied.discountBps().bps()).as("v2 never affects a v1 entitlement").isEqualTo(500);
        assertThat(applied.discountAmount()).isEqualTo(inr(4_999));
        assertThat(membershipRows(customer)).as("evaluation writes nothing").isEqualTo(before);
    }

    @Test
    void cancel_at_period_end_keeps_the_benefit_because_membership_still_entitles() {
        CustomerId customer = CustomerId.generate();
        grant(customer);
        termination.cancelAtPeriodEnd(customer);

        assertThat(standalone(rule(1, MIN, 500)).evaluate(customer, inr(MIN)))
                .isInstanceOf(BenefitEvaluation.Applied.class);
    }

    @Test
    void a_revoked_term_has_no_benefit_immediately_without_benefits_inspecting_lifecycle_state() {
        CustomerId customer = CustomerId.generate();
        Membership term = grant(customer);
        BenefitsEvaluationService svc = standalone(rule(1, MIN, 500));
        assertThat(svc.evaluate(customer, inr(MIN))).isInstanceOf(BenefitEvaluation.Applied.class);

        termination.revoke(term.membershipId());

        assertThat(svc.evaluate(customer, inr(MIN)))
                .isEqualTo(new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
    }

    @Test
    void a_corrupt_membership_row_is_an_integrity_failure_never_no_benefit() {
        CustomerId customer = CustomerId.generate();
        // claims the open slot but is not a valid term: Membership fails loud, Benefits maps it
        db.getCollection(MEMBERSHIPS).insertOne(new Document("_id", "MBR_" + UUID.randomUUID())
                .append("customerId", customer.value()).append("status", "ACTIVE").append("openTerm", true)
                .append("grantSource", "INTERNAL_GRANT").append("grantRef", "REF-" + UUID.randomUUID()));

        assertThatThrownBy(() -> standalone(rule(1, MIN, 500)).evaluate(customer, inr(MIN)))
                .isInstanceOfSatisfying(BenefitsFailure.class,
                        e -> assertThat(e.reason()).isEqualTo(BenefitsFailure.Reason.INTEGRITY_FAILURE));
        assertThatThrownBy(() -> new Tx(client).call(
                session -> transactional(transactionalMembershipPort, rule(1, MIN, 500))
                        .evaluate(session, customer, inr(MIN))))
                .isInstanceOfSatisfying(BenefitsFailure.class,
                        e -> assertThat(e.reason()).isEqualTo(BenefitsFailure.Reason.INTEGRITY_FAILURE));
    }

    // ---------- transactional ----------

    /** Records the session the Membership port was handed and counts calls. */
    private static final class RecordingTransactionalPort implements TransactionalMembershipEntitlementPort {
        final TransactionalMembershipEntitlementPort delegate;
        final AtomicReference<ClientSession> sessionSeen = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        RecordingTransactionalPort(TransactionalMembershipEntitlementPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MembershipEntitlement> currentEntitlement(ClientSession session, CustomerId customerId) {
            calls.incrementAndGet();
            sessionSeen.set(session);
            return delegate.currentEntitlement(session, customerId);
        }
    }

    @Test
    void the_transactional_evaluation_joins_the_callers_session_opens_no_transaction_and_sees_uncommitted_state() {
        CustomerId customer = CustomerId.generate();
        Membership term = grant(customer);
        RecordingTransactionalPort port = new RecordingTransactionalPort(transactionalMembershipPort);
        BenefitsTransactionalEvaluator evaluator = transactional(port, rule(1, MIN, 500));
        BenefitsEvaluationService outside = standalone(rule(1, MIN, 500));
        Instant revokedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Boolean outsideStillSawBenefitBeforeCommit = new Tx(client).call(session -> {
            assertThat(session.hasActiveTransaction()).isTrue();
            assertThat(evaluator.evaluate(session, customer, inr(MIN))).isInstanceOf(BenefitEvaluation.Applied.class);

            // the CALLER's own uncommitted write: a valid immediate revoke of the term
            db.getCollection(MEMBERSHIPS).updateOne(session, Filters.eq("_id", term.membershipId().value()),
                    Updates.combine(Updates.set("status", "REVOKED"), Updates.set("revokedAt", Date.from(revokedAt)),
                            Updates.set("updatedAt", Date.from(revokedAt)), Updates.set("version", 2L),
                            Updates.unset("openTerm")));

            assertThat(evaluator.evaluate(session, customer, inr(MIN)))
                    .as("the caller's uncommitted revoke is visible to the in-transaction evaluation")
                    .isEqualTo(new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
            assertThat(session.hasActiveTransaction()).as("still the caller's one transaction").isTrue();
            assertThat(port.sessionSeen.get()).as("the PROVIDED session was used").isSameAs(session);
            // a standalone evaluation cannot see the uncommitted revoke: proof there is no fallback to it
            return outside.evaluate(customer, inr(MIN)) instanceof BenefitEvaluation.Applied;
        });

        assertThat(outsideStillSawBenefitBeforeCommit).isTrue();
        assertThat(port.calls.get()).isEqualTo(2);
        assertThat(outside.evaluate(customer, inr(MIN))).as("visible once committed")
                .isEqualTo(new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
    }

    @Test
    void the_transactional_evaluation_emits_zero_metrics_on_applied_no_benefit_failure_and_across_a_retry() {
        CustomerId entitled = CustomerId.generate();
        grant(entitled);
        CustomerId nobody = CustomerId.generate();
        CustomerId corrupt = CustomerId.generate();
        db.getCollection(MEMBERSHIPS).insertOne(new Document("_id", "MBR_" + UUID.randomUUID())
                .append("customerId", corrupt.value()).append("status", "ACTIVE").append("openTerm", true)
                .append("grantSource", "INTERNAL_GRANT").append("grantRef", "REF-" + UUID.randomUUID()));
        BenefitsTransactionalEvaluator evaluator = transactional(transactionalMembershipPort, rule(1, MIN, 500));
        java.util.Set<String> before = benefitsAndMembershipMeters();

        new Tx(client).call(session -> evaluator.evaluate(session, entitled, inr(MIN)));          // applied
        new Tx(client).call(session -> evaluator.evaluate(session, nobody, inr(MIN)));             // no benefit
        assertThatThrownBy(() -> new Tx(client).call(session -> evaluator.evaluate(session, corrupt, inr(MIN))))
                .isInstanceOf(BenefitsFailure.class);                                              // failure
        assertThatThrownBy(() -> new Tx(client).call(session -> evaluator.evaluate(session, null, inr(MIN))))
                .isInstanceOf(BenefitsFailure.class);                                              // invalid input

        AtomicInteger attempts = new AtomicInteger();
        MongoException transientError = new MongoException("simulated transient error");
        transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        BenefitsTransactionalEvaluator flaky = transactional((s, c) -> {
            if (attempts.incrementAndGet() == 1) {
                throw transientError;
            }
            return transactionalMembershipPort.currentEntitlement(s, c);
        }, rule(1, MIN, 500));
        BenefitEvaluation viaRetry = new Tx(client).call(session -> flaky.evaluate(session, entitled, inr(MIN)));

        assertThat(viaRetry).as("the caller's own retry loop saw the untouched transient label")
                .isInstanceOf(BenefitEvaluation.Applied.class);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(benefitsAndMembershipMeters()).as("ZERO metrics from the transactional evaluation")
                .isEqualTo(before);
    }

    @Test
    void the_standalone_evaluation_records_only_a_bounded_failure_metric() {
        CustomerId corrupt = CustomerId.generate();
        db.getCollection(MEMBERSHIPS).insertOne(new Document("_id", "MBR_" + UUID.randomUUID())
                .append("customerId", corrupt.value()).append("status", "ACTIVE").append("openTerm", true)
                .append("grantSource", "INTERNAL_GRANT").append("grantRef", "REF-" + UUID.randomUUID()));
        double before = springRegistry.find("benefits_failure").tag("operation", "evaluate")
                .tag("reason", "integrity_failure").counters().stream().mapToDouble(c -> c.count()).sum();

        assertThatThrownBy(() -> standalone(rule(1, MIN, 500)).evaluate(corrupt, inr(MIN)))
                .isInstanceOf(BenefitsFailure.class);

        double after = springRegistry.find("benefits_failure").tag("operation", "evaluate")
                .tag("reason", "integrity_failure").counters().stream().mapToDouble(c -> c.count()).sum();
        assertThat(after - before).isEqualTo(1.0);
        springRegistry.getMeters().stream().map(Meter::getId).filter(id -> id.getName().startsWith("benefits"))
                .forEach(id -> assertThat(id.getTags().stream().map(t -> t.getKey()).toList())
                        .containsExactlyInAnyOrder("operation", "reason"));
    }
}
