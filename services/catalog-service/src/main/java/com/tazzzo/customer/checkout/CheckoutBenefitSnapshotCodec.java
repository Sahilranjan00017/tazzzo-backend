package com.tazzzo.customer.checkout;

import com.tazzzo.benefits.BenefitEvaluation;
import org.bson.Document;

import java.util.Set;

/**
 * The persisted shape of {@link CheckoutBenefitSnapshot}: ONE nested {@code benefits} document on the quote.
 * <pre>
 *   NO_BENEFIT: { outcome, eligibleSubtotalPaise, noBenefitReason }
 *   APPLIED   : { outcome, eligibleSubtotalPaise, discountPaise, discountBps }
 * </pre>
 * Reconstruction is STRICT (the Checkout convention: nothing is defaulted or repaired): an unknown outcome or reason,
 * a missing/foreign field, a wrong BSON type, an explicit null, an empty document or a violated invariant throws. The
 * ABSENCE of the whole field is a legacy quote and is decided by the caller. Fail-loud corruption is mapped by the
 * public boundary to a safe 500 {@code INTERNAL}, like every other corrupt quote field.
 */
final class CheckoutBenefitSnapshotCodec {

    static final String FIELD = "benefits";

    private static final Set<String> NO_BENEFIT_KEYS = Set.of("outcome", "eligibleSubtotalPaise", "noBenefitReason");
    private static final Set<String> APPLIED_KEYS = Set.of("outcome", "eligibleSubtotalPaise", "discountPaise",
            "discountBps");

    private CheckoutBenefitSnapshotCodec() {
    }

    static Document toDocument(CheckoutBenefitSnapshot snapshot) {
        if (snapshot instanceof CheckoutBenefitSnapshot.NoBenefit n) {
            return new Document("outcome", "NO_BENEFIT").append("eligibleSubtotalPaise", n.eligibleSubtotalPaise())
                    .append("noBenefitReason", n.reason().name());
        }
        CheckoutBenefitSnapshot.Applied a = (CheckoutBenefitSnapshot.Applied) snapshot;
        return new Document("outcome", "APPLIED").append("eligibleSubtotalPaise", a.eligibleSubtotalPaise())
                .append("discountPaise", a.discountPaise()).append("discountBps", a.discountBps());
    }

    /** {@code raw} is the PRESENT value of the {@code benefits} field (an explicit null is corruption too). */
    static CheckoutBenefitSnapshot fromDocument(Object raw) {
        if (!(raw instanceof Document d)) {
            throw new IllegalArgumentException("checkout quote benefits snapshot must be a document");
        }
        String outcome = requireString(d, "outcome");
        switch (outcome) {
            case "NO_BENEFIT" -> {
                requireExactKeys(d, NO_BENEFIT_KEYS);
                return new CheckoutBenefitSnapshot.NoBenefit(requireIntegral(d, "eligibleSubtotalPaise"),
                        BenefitEvaluation.NoBenefitReason.valueOf(requireString(d, "noBenefitReason")));
            }
            case "APPLIED" -> {
                requireExactKeys(d, APPLIED_KEYS);
                return new CheckoutBenefitSnapshot.Applied(requireIntegral(d, "eligibleSubtotalPaise"),
                        requireIntegral(d, "discountPaise"), Math.toIntExact(requireIntegral(d, "discountBps")));
            }
            default -> throw new IllegalArgumentException("unknown checkout quote benefits outcome");
        }
    }

    private static void requireExactKeys(Document d, Set<String> expected) {
        if (!d.keySet().equals(expected)) {
            throw new IllegalArgumentException("checkout quote benefits snapshot has missing or foreign fields");
        }
    }

    private static String requireString(Document d, String field) {
        if (!(d.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("checkout quote benefits snapshot field missing/invalid: " + field);
        }
        return s;
    }

    /** int32 or int64 only: a double, decimal or string is corruption, never silently truncated. */
    private static long requireIntegral(Document d, String field) {
        Object v = d.get(field);
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException("checkout quote benefits snapshot field missing/invalid: " + field);
    }
}
