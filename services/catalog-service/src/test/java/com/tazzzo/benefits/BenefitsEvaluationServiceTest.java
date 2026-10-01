package com.tazzzo.benefits;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Money;
import com.tazzzo.membership.MembershipEntitlement;
import com.tazzzo.membership.MembershipEntitlementPort;
import com.tazzzo.membership.MembershipFailure;
import com.tazzzo.membership.MembershipId;
import com.tazzzo.membership.TransactionalMembershipEntitlementPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.tazzzo.benefits.BenefitRuleTest.PLAN;
import static com.tazzzo.benefits.BenefitRuleTest.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic Benefits evaluation, standalone and transactional, against fake Membership ports. */
class BenefitsEvaluationServiceTest {

    // TEST FIXTURES only (never launch policy): 5% off a subtotal of at least 50,000 paise.
    private static final long MIN = 50_000;
    private static final MembershipId MBR = MembershipId.generate();
    private static final CustomerId CUSTOMER = CustomerId.generate();
    private static final Instant UNTIL = Instant.parse("2027-02-28T04:30:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static MembershipEntitlement entitlement(int version) {
        return new MembershipEntitlement(MBR, PLAN, version, UNTIL);
    }

    private static Money inr(long paise) {
        return Money.ofInrPaise(paise);
    }

    private BenefitsEvaluationService standalone(MembershipEntitlementPort port, BenefitRule... rules) {
        return new BenefitsEvaluationService(port, new ConfigBackedBenefitRuleSource(List.of(rules)),
                new BenefitsObservability(registry));
    }

    private static BenefitsEvaluationService noRules(MembershipEntitlementPort port) {
        return new BenefitsEvaluationService(port, new ConfigBackedBenefitRuleSource(List.of()),
                new BenefitsObservability(new SimpleMeterRegistry()));
    }

    private static MembershipEntitlementPort entitled(int version) {
        return c -> Optional.of(entitlement(version));
    }

    private static void assertFailure(Runnable call, BenefitsFailure.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BenefitsFailure.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static BenefitEvaluation.NoBenefit noBenefit(BenefitEvaluation e) {
        assertThat(e).isInstanceOf(BenefitEvaluation.NoBenefit.class);
        return (BenefitEvaluation.NoBenefit) e;
    }

    // ---------- standalone ----------

    @Test
    void no_membership_is_a_normal_no_benefit_and_records_nothing() {
        BenefitsEvaluationService svc = standalone(c -> Optional.empty(), rule(1, MIN, 500));

        assertThat(noBenefit(svc.evaluate(CUSTOMER, inr(MIN * 10))).reason())
                .isEqualTo(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void an_entitlement_without_a_rule_for_its_exact_version_is_no_rule_never_a_fallback() {
        // only v2 is configured; the entitlement is v1 => no rule, NOT v2's
        BenefitsEvaluationService svc = standalone(entitled(1), rule(2, 1, 9_999));
        assertThat(noBenefit(svc.evaluate(CUSTOMER, inr(MIN * 10))).reason())
                .isEqualTo(BenefitEvaluation.NoBenefitReason.NO_RULE);

        // an unknown future version, with v1 configured: no latest/lower-version fallback
        assertThat(noBenefit(standalone(entitled(3), rule(1, MIN, 500), rule(2, MIN, 700))
                .evaluate(CUSTOMER, inr(MIN * 10))).reason()).isEqualTo(BenefitEvaluation.NoBenefitReason.NO_RULE);

        // and the production default (no rules at all) invents no discount
        assertThat(noBenefit(noRules(entitled(1)).evaluate(CUSTOMER, inr(MIN * 10))).reason())
                .isEqualTo(BenefitEvaluation.NoBenefitReason.NO_RULE);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void the_threshold_is_inclusive_minus_one_paise_not_applied_at_and_above_applied() {
        BenefitsEvaluationService svc = standalone(entitled(1), rule(1, MIN, 500));

        assertThat(noBenefit(svc.evaluate(CUSTOMER, inr(MIN - 1))).reason())
                .isEqualTo(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE);
        assertThat(svc.evaluate(CUSTOMER, inr(MIN))).isInstanceOf(BenefitEvaluation.Applied.class);
        assertThat(svc.evaluate(CUSTOMER, inr(MIN + 1))).isInstanceOf(BenefitEvaluation.Applied.class);
        assertThat(noBenefit(svc.evaluate(CUSTOMER, inr(0))).reason())
                .isEqualTo(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE);
    }

    @Test
    void an_applied_result_carries_the_authority_a_money_snapshot_needs() {
        BenefitsEvaluationService svc = standalone(entitled(1), rule(1, MIN, 500));

        BenefitEvaluation.Applied applied = (BenefitEvaluation.Applied) svc.evaluate(CUSTOMER, inr(99_999));

        assertThat(applied.membershipId()).isEqualTo(MBR);
        assertThat(applied.planId()).isEqualTo(PLAN);
        assertThat(applied.planVersion()).isEqualTo(1);
        assertThat(applied.eligibleSubtotal()).isEqualTo(inr(99_999));
        assertThat(applied.discountBps()).isEqualTo(new DiscountBps(500));
        assertThat(applied.discountAmount()).as("floor(99999*500/10000)=4999 (4999.95)").isEqualTo(inr(4_999));
        assertThat(applied.discountAmount().paise()).isLessThanOrEqualTo(applied.eligibleSubtotal().paise());
    }

    @Test
    void floor_rounding_examples_and_the_discount_never_exceeds_the_subtotal() {
        BenefitsEvaluationService svc = standalone(entitled(1), rule(1, MIN, 500));
        long[][] cases = {{50_000, 2_500}, {50_019, 2_500}, {50_020, 2_501}, {123_457, 6_172}, {1_000_000, 50_000}};
        for (long[] c : cases) {
            BenefitEvaluation.Applied a = (BenefitEvaluation.Applied) svc.evaluate(CUSTOMER, inr(c[0]));
            assertThat(a.discountAmount().paise()).as("subtotal %d", c[0]).isEqualTo(c[1]);
        }
        BenefitEvaluation.Applied full = (BenefitEvaluation.Applied)
                standalone(entitled(1), rule(1, 1, 10_000)).evaluate(CUSTOMER, inr(777));
        assertThat(full.discountAmount()).as("100% discounts exactly the subtotal, never more").isEqualTo(inr(777));
    }

    @Test
    void a_floor_discount_of_zero_paise_is_not_an_applied_benefit() {
        // 1 bps of 9,999 paise floors to 0: not a real discount
        BenefitsEvaluationService svc = standalone(entitled(1), rule(1, 1, 1));
        assertThat(noBenefit(svc.evaluate(CUSTOMER, inr(9_999))).reason())
                .isEqualTo(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE);
        assertThat(svc.evaluate(CUSTOMER, inr(10_000))).isInstanceOf(BenefitEvaluation.Applied.class);
    }

    @Test
    void the_exact_entitlement_plan_version_selects_the_rule_and_a_later_version_never_leaks_in() {
        BenefitRule v1 = rule(1, MIN, 500);
        BenefitRule v2 = rule(2, MIN, 1_000);
        long subtotal = 100_000;

        BenefitEvaluation.Applied onV1 = (BenefitEvaluation.Applied)
                standalone(entitled(1), v1, v2).evaluate(CUSTOMER, inr(subtotal));
        BenefitEvaluation.Applied onV2 = (BenefitEvaluation.Applied)
                standalone(entitled(2), v1, v2).evaluate(CUSTOMER, inr(subtotal));

        assertThat(onV1.discountBps().bps()).isEqualTo(500);
        assertThat(onV1.discountAmount()).isEqualTo(inr(5_000));
        assertThat(onV2.discountBps().bps()).isEqualTo(1_000);
        assertThat(onV2.discountAmount()).isEqualTo(inr(10_000));
        assertThat(onV1.planVersion()).isEqualTo(1);
        assertThat(onV2.planVersion()).isEqualTo(2);
    }

    @Test
    void invalid_input_is_a_typed_failure_and_the_membership_port_is_never_asked() {
        AtomicInteger calls = new AtomicInteger();
        BenefitsEvaluationService svc = standalone(c -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, rule(1, MIN, 500));

        assertFailure(() -> svc.evaluate(null, inr(MIN)), BenefitsFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> svc.evaluate(CUSTOMER, null), BenefitsFailure.Reason.INVALID_REQUEST);

        assertThat(calls.get()).isZero();
        assertThat(registry.find("benefits_failure").tag("operation", "evaluate").tag("reason", "invalid_request")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void membership_failures_map_onto_the_closed_benefits_vocabulary_and_never_become_no_benefit() {
        for (var pair : new Object[][]{
                {MembershipFailure.Reason.INVALID_REQUEST, BenefitsFailure.Reason.INVALID_REQUEST},
                {MembershipFailure.Reason.UNAVAILABLE, BenefitsFailure.Reason.UNAVAILABLE},
                {MembershipFailure.Reason.INTEGRITY_FAILURE, BenefitsFailure.Reason.INTEGRITY_FAILURE},
                // no entitlement READ can produce these; if one ever surfaced it is unexplained => integrity
                {MembershipFailure.Reason.NOT_FOUND, BenefitsFailure.Reason.INTEGRITY_FAILURE},
                {MembershipFailure.Reason.INVALID_TRANSITION, BenefitsFailure.Reason.INTEGRITY_FAILURE}}) {
            SimpleMeterRegistry r = new SimpleMeterRegistry();
            BenefitsEvaluationService svc = new BenefitsEvaluationService(
                    c -> {
                        throw new MembershipFailure((MembershipFailure.Reason) pair[0], "boom");
                    },
                    new ConfigBackedBenefitRuleSource(List.of(rule(1, MIN, 500))), new BenefitsObservability(r));

            BenefitsFailure.Reason expected = (BenefitsFailure.Reason) pair[1];
            assertFailure(() -> svc.evaluate(CUSTOMER, inr(MIN)), expected);
            assertThat(r.find("benefits_failure").tag("operation", "evaluate")
                    .tag("reason", expected.name().toLowerCase()).counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    void the_failure_message_never_leaks_membership_text() {
        BenefitsEvaluationService svc = standalone(c -> {
            throw new MembershipFailure(MembershipFailure.Reason.INTEGRITY_FAILURE, "customer cus_secret row corrupt");
        }, rule(1, MIN, 500));

        assertThatThrownBy(() -> svc.evaluate(CUSTOMER, inr(MIN))).isInstanceOf(BenefitsFailure.class)
                .hasMessageNotContaining("cus_secret").hasNoCause();
    }

    @Test
    void a_failing_rule_source_is_a_rule_configuration_failure_not_no_benefit() {
        BenefitRuleSource broken = (p, v) -> {
            throw new IllegalStateException("rule store exploded");
        };
        BenefitsEvaluationService svc = new BenefitsEvaluationService(entitled(1), broken,
                new BenefitsObservability(registry));

        assertFailure(() -> svc.evaluate(CUSTOMER, inr(MIN)), BenefitsFailure.Reason.RULE_CONFIGURATION_FAILURE);
        assertThat(registry.find("benefits_failure").tag("reason", "rule_configuration_failure").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void metric_tags_are_closed_enums_only() {
        BenefitsEvaluationService svc = standalone(c -> {
            throw new MembershipFailure(MembershipFailure.Reason.UNAVAILABLE, "down");
        }, rule(1, MIN, 500));
        assertFailure(() -> svc.evaluate(CUSTOMER, inr(MIN)), BenefitsFailure.Reason.UNAVAILABLE);

        assertThat(registry.getMeters()).hasSize(1);
        var id = registry.getMeters().get(0).getId();
        assertThat(id.getName()).isEqualTo("benefits_failure");
        assertThat(id.getTags()).extracting(t -> t.getKey() + "=" + t.getValue())
                .containsExactlyInAnyOrder("operation=evaluate", "reason=unavailable");
    }

    @Test
    void a_registry_fault_never_changes_the_outcome() {
        io.micrometer.core.instrument.MeterRegistry exploding = new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                throw new IllegalStateException("registry down");
            }
        };
        BenefitsEvaluationService svc = new BenefitsEvaluationService(c -> {
            throw new MembershipFailure(MembershipFailure.Reason.UNAVAILABLE, "down");
        }, new ConfigBackedBenefitRuleSource(List.of()), new BenefitsObservability(exploding));

        assertFailure(() -> svc.evaluate(CUSTOMER, inr(MIN)), BenefitsFailure.Reason.UNAVAILABLE);
    }

    @Test
    void the_applied_result_enforces_its_own_invariants() {
        Money subtotal = inr(100_000);
        DiscountBps bps = new DiscountBps(500);
        assertThatThrownBy(() -> new BenefitEvaluation.Applied(MBR, PLAN, 1, subtotal, inr(5_001), bps))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitEvaluation.Applied(MBR, PLAN, 1, subtotal, inr(0), new DiscountBps(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitEvaluation.Applied(MBR, PLAN, 0, subtotal, inr(5_000), bps))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitEvaluation.NoBenefit(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new BenefitEvaluation.Applied(MBR, PLAN, 1, subtotal, inr(5_000), bps)).isNotNull();
    }

    // ---------- transactional (fake Membership port; real-Mongo behaviour is in BenefitsEvaluationIT) ----------

    @Test
    void the_transactional_evaluator_passes_the_callers_session_to_the_transactional_membership_port() {
        ClientSession session = Mockito.mock(ClientSession.class);
        AtomicReference<ClientSession> seen = new AtomicReference<>();
        TransactionalMembershipEntitlementPort port = (s, c) -> {
            seen.set(s);
            return Optional.of(entitlement(1));
        };
        BenefitsTransactionalEvaluator tx = new BenefitsTransactionalEvaluator(port,
                new ConfigBackedBenefitRuleSource(List.of(rule(1, MIN, 500))));

        BenefitEvaluation result = tx.evaluate(session, CUSTOMER, inr(MIN));

        assertThat(result).isInstanceOf(BenefitEvaluation.Applied.class);
        assertThat(seen.get()).isSameAs(session);
        Mockito.verifyNoInteractions(session); // it neither starts, commits nor aborts anything
    }

    @Test
    void the_transactional_evaluator_decides_exactly_like_the_standalone_one() {
        ClientSession session = Mockito.mock(ClientSession.class);
        BenefitRuleSource rules = new ConfigBackedBenefitRuleSource(List.of(rule(1, MIN, 500), rule(2, MIN, 900)));
        for (Optional<MembershipEntitlement> e : List.of(Optional.<MembershipEntitlement>empty(),
                Optional.of(entitlement(1)), Optional.of(entitlement(2)), Optional.of(entitlement(7)))) {
            for (long subtotal : List.of(0L, MIN - 1, MIN, MIN + 1, 123_457L)) {
                BenefitEvaluation viaStandalone = new BenefitsEvaluationService(c -> e, rules,
                        new BenefitsObservability(new SimpleMeterRegistry())).evaluate(CUSTOMER, inr(subtotal));
                BenefitEvaluation viaTx = new BenefitsTransactionalEvaluator((s, c) -> e, rules)
                        .evaluate(session, CUSTOMER, inr(subtotal));
                assertThat(viaTx).isEqualTo(viaStandalone);
            }
        }
    }

    @Test
    void the_transactional_evaluator_rejects_a_missing_session_and_invalid_input() {
        BenefitsTransactionalEvaluator tx = new BenefitsTransactionalEvaluator((s, c) -> Optional.empty(),
                new ConfigBackedBenefitRuleSource(List.of()));
        ClientSession session = Mockito.mock(ClientSession.class);

        assertThatThrownBy(() -> tx.evaluate(null, CUSTOMER, inr(1))).isInstanceOf(IllegalArgumentException.class);
        assertFailure(() -> tx.evaluate(session, null, inr(1)), BenefitsFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> tx.evaluate(session, CUSTOMER, null), BenefitsFailure.Reason.INVALID_REQUEST);
    }

    @Test
    void the_transactional_evaluator_maps_membership_failures_and_never_collapses_them_to_no_benefit() {
        ClientSession session = Mockito.mock(ClientSession.class);
        for (var pair : new Object[][]{
                {MembershipFailure.Reason.INVALID_REQUEST, BenefitsFailure.Reason.INVALID_REQUEST},
                {MembershipFailure.Reason.UNAVAILABLE, BenefitsFailure.Reason.UNAVAILABLE},
                {MembershipFailure.Reason.INTEGRITY_FAILURE, BenefitsFailure.Reason.INTEGRITY_FAILURE}}) {
            BenefitsTransactionalEvaluator tx = new BenefitsTransactionalEvaluator((s, c) -> {
                throw new MembershipFailure((MembershipFailure.Reason) pair[0], "x");
            }, new ConfigBackedBenefitRuleSource(List.of()));
            assertFailure(() -> tx.evaluate(session, CUSTOMER, inr(MIN)), (BenefitsFailure.Reason) pair[1]);
        }
    }

    @Test
    void a_transient_driver_error_propagates_untouched_for_the_callers_retry() {
        ClientSession session = Mockito.mock(ClientSession.class);
        MongoException transientError = new MongoException("simulated");
        transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        BenefitsTransactionalEvaluator tx = new BenefitsTransactionalEvaluator((s, c) -> {
            throw transientError;
        }, new ConfigBackedBenefitRuleSource(List.of(rule(1, MIN, 500))));

        assertThatThrownBy(() -> tx.evaluate(session, CUSTOMER, inr(MIN))).isSameAs(transientError);
    }
}
