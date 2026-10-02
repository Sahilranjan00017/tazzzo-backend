package com.tazzzo.customer.order;

import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.customer.checkout.CheckoutBenefitSnapshot;
import com.tazzzo.customer.checkout.CheckoutMoneySnapshot;
import com.tazzzo.customer.checkout.CheckoutQuote;

/**
 * Test helpers that give a quote the BINDING money a real checkout now always persists. Order placement refuses a quote with no
 * money (it was never shown a payable) or whose money differs from the live authoritative money, so every fixture that expects a
 * placement to succeed must carry money that equals what Benefits will evaluate.
 */
final class TestQuotes {

    /** The customer has no membership: discount 0, payable == subtotal. */
    static CheckoutQuote bindNoBenefit(CheckoutQuote q) {
        return bind(q, new CheckoutBenefitSnapshot.NoBenefit(q.subtotalPaise(), BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
    }

    /** An applied discount of exactly {@code discountPaise} (bps is informational only). */
    static CheckoutQuote bindApplied(CheckoutQuote q, long discountPaise, int discountBps) {
        return bind(q, new CheckoutBenefitSnapshot.Applied(q.subtotalPaise(), discountPaise, discountBps));
    }

    static CheckoutQuote bind(CheckoutQuote q, CheckoutBenefitSnapshot benefit) {
        long discount = benefit instanceof CheckoutBenefitSnapshot.Applied a ? a.discountPaise() : 0L;
        return new CheckoutQuote(q.quoteId(), q.cartVersion(), q.addressId(), q.addressVersion(), q.lines(), q.itemCount(),
                q.subtotalPaise(), q.currency(), q.createdAt(), q.expiresAt(), benefit,
                new CheckoutMoneySnapshot(q.subtotalPaise(), discount));
    }

    private TestQuotes() {
    }
}
