package com.tazzzo.customer.order;

import com.tazzzo.benefits.BenefitEvaluation;

/**
 * The immutable, Order-owned record of the AUTHORITATIVE Benefits evaluation performed inside the placement
 * transaction. Exactly two shapes, each with only the fields that exist for it (conditional presence — no
 * placeholder zero, null id or version 0 anywhere):
 * <ul>
 *   <li>{@link NoBenefit} — a normal non-applied outcome ({@code NO_MEMBERSHIP}, {@code NO_RULE},
 *       {@code NOT_ELIGIBLE}); a successful placement, never a failure;</li>
 *   <li>{@link Applied} — the aggregate discount and the exact authority behind it (term, plan, version, rate).</li>
 * </ul>
 * It is copied from {@code BenefitEvaluation}, never re-derived: Order performs no Benefits arithmetic and does not
 * consult Membership. {@code eligibleSubtotalPaise} is the canonical merchandise subtotal of the Order (the Order
 * constructor enforces equality). No discount is allocated to lines and no net/payable total is derived here: Pricing's
 * {@code unitPricePaise}/{@code lineTotalPaise}/{@code subtotalPaise} stay the canonical merchandise money.
 *
 * <p>Presence means "this Order was evaluated by the Benefits-aware placement"; ABSENCE (on the Order) means a legacy
 * Order created before it, and never means "no benefit".
 */
public sealed interface OrderBenefitSnapshot {

    long eligibleSubtotalPaise();

    record NoBenefit(long eligibleSubtotalPaise, BenefitEvaluation.NoBenefitReason reason)
            implements OrderBenefitSnapshot {
        public NoBenefit {
            if (eligibleSubtotalPaise < 0) {
                throw new IllegalArgumentException("eligibleSubtotalPaise must be >= 0: " + eligibleSubtotalPaise);
            }
            if (reason == null) {
                throw new IllegalArgumentException("noBenefitReason required");
            }
        }
    }

    record Applied(long eligibleSubtotalPaise, long discountPaise, int discountBps, String membershipId,
                   String planId, int planVersion) implements OrderBenefitSnapshot {
        public Applied {
            if (eligibleSubtotalPaise < 0) {
                throw new IllegalArgumentException("eligibleSubtotalPaise must be >= 0: " + eligibleSubtotalPaise);
            }
            if (discountPaise < 1) {
                throw new IllegalArgumentException("discountPaise must be >= 1: " + discountPaise);
            }
            if (discountPaise > eligibleSubtotalPaise) {
                throw new IllegalArgumentException("discountPaise must not exceed the eligible subtotal");
            }
            if (discountBps < 1 || discountBps > 10_000) {
                throw new IllegalArgumentException("discountBps must be within 1..10000: " + discountBps);
            }
            if (membershipId == null || membershipId.isBlank()) {
                throw new IllegalArgumentException("membershipId required");
            }
            if (planId == null || planId.isBlank()) {
                throw new IllegalArgumentException("planId required");
            }
            if (planVersion < 1) {
                throw new IllegalArgumentException("planVersion must be >= 1: " + planVersion);
            }
        }
    }

    /**
     * Copies the Benefits result exactly. {@code orderSubtotalPaise} is what Order evaluated; an {@code Applied} result
     * that reports a different eligible subtotal or a non-INR currency is an internal inconsistency
     * ({@link IllegalArgumentException}, mapped by the caller to {@code INTEGRITY_FAILURE}).
     */
    static OrderBenefitSnapshot from(BenefitEvaluation evaluation, long orderSubtotalPaise) {
        if (evaluation instanceof BenefitEvaluation.NoBenefit n) {
            return new NoBenefit(orderSubtotalPaise, n.reason());
        }
        if (evaluation instanceof BenefitEvaluation.Applied a) {
            if (a.eligibleSubtotal().paise() != orderSubtotalPaise
                    || a.eligibleSubtotal().currency() != com.tazzzo.common.money.Currency.INR) {
                throw new IllegalArgumentException("benefit evaluation does not match the order subtotal");
            }
            return new Applied(orderSubtotalPaise, a.discountAmount().paise(), a.discountBpsValue(),
                    a.membershipIdValue(), a.planId(), a.planVersion());
        }
        throw new IllegalArgumentException("unknown benefit evaluation");
    }
}
