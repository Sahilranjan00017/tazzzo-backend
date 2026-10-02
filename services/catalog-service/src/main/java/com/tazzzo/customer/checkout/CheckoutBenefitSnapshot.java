package com.tazzzo.customer.checkout;

import com.tazzzo.benefits.BenefitEvaluation;

/**
 * The immutable, Checkout-owned record of the Benefits evaluation the Checkout service froze when it
 * created the quote (the basis of the quote's binding money), persisted with the quote and never re-evaluated (an idempotent replay and a GET return it
 * unchanged). INTERNAL only: it is not in {@code CheckoutQuoteDto}, the OpenAPI schema or any HTTP response.
 *
 * <p>Order placement still re-evaluates Benefits transactionally (its own snapshot, with the membership identity, is
 * what the Order records), but only to VERIFY the money the customer reviewed: if a later Membership or Benefits
 * change makes the resulting money differ from this quote's, the Order is refused ({@code PAYABLE_CHANGED}) and a
 * fresh quote is required; the live result is never substituted silently.
 * Deliberately a separate type from the Order's snapshot (different owner, different purpose; Checkout never
 * depends on Order). Exactly two shapes, conditional presence, no placeholders:
 * <ul>
 *   <li>{@link NoBenefit} — a normal outcome ({@code NO_MEMBERSHIP}, {@code NO_RULE}, {@code NOT_ELIGIBLE}); a
 *       successful quote, never a failure;</li>
 *   <li>{@link Applied} — the aggregate discount and its rate. The Membership/plan identity is deliberately NOT
 *       recorded: Checkout has no need for it (it is Order authority metadata).</li>
 * </ul>
 * It is projected from {@code BenefitEvaluation}; Checkout performs no Benefits arithmetic. The eligible subtotal is
 * exactly the quote's canonical merchandise subtotal (the {@link CheckoutQuote} constructor enforces equality).
 * ABSENCE on a quote means a legacy quote created before this slice, never "no benefit".
 */
public sealed interface CheckoutBenefitSnapshot {

    long eligibleSubtotalPaise();

    record NoBenefit(long eligibleSubtotalPaise, BenefitEvaluation.NoBenefitReason reason)
            implements CheckoutBenefitSnapshot {
        public NoBenefit {
            if (eligibleSubtotalPaise < 0) {
                throw new IllegalArgumentException("eligibleSubtotalPaise must be >= 0: " + eligibleSubtotalPaise);
            }
            if (reason == null) {
                throw new IllegalArgumentException("noBenefitReason required");
            }
        }
    }

    record Applied(long eligibleSubtotalPaise, long discountPaise, int discountBps)
            implements CheckoutBenefitSnapshot {
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
        }
    }

    /**
     * Copies the Benefits result exactly. {@code quoteSubtotalPaise} is what Checkout evaluated; an {@code Applied}
     * result for a different subtotal or a non-INR currency is an internal inconsistency
     * ({@link IllegalArgumentException}, treated by the caller as an integrity defect).
     */
    static CheckoutBenefitSnapshot from(BenefitEvaluation evaluation, long quoteSubtotalPaise) {
        if (evaluation instanceof BenefitEvaluation.NoBenefit n) {
            return new NoBenefit(quoteSubtotalPaise, n.reason());
        }
        if (evaluation instanceof BenefitEvaluation.Applied a) {
            if (a.eligibleSubtotal().paise() != quoteSubtotalPaise
                    || a.eligibleSubtotal().currency() != com.tazzzo.common.money.Currency.INR) {
                throw new IllegalArgumentException("benefit evaluation does not match the quote subtotal");
            }
            return new Applied(quoteSubtotalPaise, a.discountAmount().paise(), a.discountBpsValue());
        }
        throw new IllegalArgumentException("unknown benefit evaluation");
    }
}
