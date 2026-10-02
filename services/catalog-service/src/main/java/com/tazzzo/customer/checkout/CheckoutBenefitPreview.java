package com.tazzzo.customer.checkout;

/**
 * The PUBLIC-SAFE projection of a quote's stored {@link CheckoutBenefitSnapshot}: the only Benefits view the
 * HTTP layer ever sees. It is deliberately structurally incapable of carrying anything internal: no no-benefit reason
 * (NO_MEMBERSHIP / NO_RULE / NOT_ELIGIBLE all collapse to {@link NotApplied}), no eligible subtotal (the quote's own
 * canonical {@code subtotalPaise} already is that), no Membership or plan identity.
 *
 * <p>Pure projection of PERSISTED values: it never re-evaluates Benefits, reads Membership or rules, or recomputes a
 * discount from the rate. ABSENCE (no preview at all) is a legacy quote created before Benefits preview existed and is
 * represented by the absence of this value, never by {@link NotApplied}: {@code NotApplied} means "Benefits WAS
 * evaluated and no benefit applied".
 */
sealed interface CheckoutBenefitPreview {

    record NotApplied() implements CheckoutBenefitPreview {
    }

    record Applied(long discountPaise, int discountBps) implements CheckoutBenefitPreview {
        public Applied {
            if (discountPaise < 1) {
                throw new IllegalArgumentException("discountPaise must be >= 1: " + discountPaise);
            }
            if (discountBps < 1 || discountBps > 10_000) {
                throw new IllegalArgumentException("discountBps must be within 1..10000: " + discountBps);
            }
        }
    }

    static CheckoutBenefitPreview from(CheckoutBenefitSnapshot snapshot) {
        return switch (snapshot) {
            case CheckoutBenefitSnapshot.NoBenefit n -> new NotApplied();
            case CheckoutBenefitSnapshot.Applied a -> new Applied(a.discountPaise(), a.discountBps());
        };
    }
}
