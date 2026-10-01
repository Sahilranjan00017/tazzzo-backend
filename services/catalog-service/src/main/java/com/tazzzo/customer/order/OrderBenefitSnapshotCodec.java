package com.tazzzo.customer.order;

import com.tazzzo.benefits.BenefitEvaluation;
import org.bson.Document;

import java.util.Set;

/**
 * The persisted shape of {@link OrderBenefitSnapshot}: ONE nested {@code benefits} document with conditional presence.
 * <pre>
 *   NO_BENEFIT: { outcome, eligibleSubtotalPaise, noBenefitReason }
 *   APPLIED   : { outcome, eligibleSubtotalPaise, discountPaise, discountBps, membershipId, planId, planVersion }
 * </pre>
 * Reconstruction is STRICT, the same fail-loud convention as every other Order field: an unknown outcome or reason, a
 * missing/foreign field, a wrong BSON type or any violated invariant throws and is never dropped, defaulted or
 * repaired. (The customer HTTP boundary maps a corrupt stored Order to a safe 500, and the placement path never
 * reads one without this check.) The ABSENCE of the whole document is the legacy Order and is decided by the caller.
 */
final class OrderBenefitSnapshotCodec {

    static final String FIELD = "benefits";

    private static final Set<String> NO_BENEFIT_KEYS = Set.of("outcome", "eligibleSubtotalPaise", "noBenefitReason");
    private static final Set<String> APPLIED_KEYS = Set.of("outcome", "eligibleSubtotalPaise", "discountPaise",
            "discountBps", "membershipId", "planId", "planVersion");

    private OrderBenefitSnapshotCodec() {
    }

    static Document toDocument(OrderBenefitSnapshot snapshot) {
        if (snapshot instanceof OrderBenefitSnapshot.NoBenefit n) {
            return new Document("outcome", "NO_BENEFIT").append("eligibleSubtotalPaise", n.eligibleSubtotalPaise())
                    .append("noBenefitReason", n.reason().name());
        }
        OrderBenefitSnapshot.Applied a = (OrderBenefitSnapshot.Applied) snapshot;
        return new Document("outcome", "APPLIED").append("eligibleSubtotalPaise", a.eligibleSubtotalPaise())
                .append("discountPaise", a.discountPaise()).append("discountBps", a.discountBps())
                .append("membershipId", a.membershipId()).append("planId", a.planId())
                .append("planVersion", a.planVersion());
    }

    /** {@code raw} is the PRESENT value of the {@code benefits} field (an explicit null is corruption too). */
    static OrderBenefitSnapshot fromDocument(Object raw) {
        if (!(raw instanceof Document d)) {
            throw new IllegalStateException("order benefits snapshot must be a document");
        }
        String outcome = requireString(d, "outcome");
        switch (outcome) {
            case "NO_BENEFIT" -> {
                requireExactKeys(d, NO_BENEFIT_KEYS);
                return new OrderBenefitSnapshot.NoBenefit(requireIntegral(d, "eligibleSubtotalPaise"),
                        BenefitEvaluation.NoBenefitReason.valueOf(requireString(d, "noBenefitReason")));
            }
            case "APPLIED" -> {
                requireExactKeys(d, APPLIED_KEYS);
                return new OrderBenefitSnapshot.Applied(requireIntegral(d, "eligibleSubtotalPaise"),
                        requireIntegral(d, "discountPaise"), Math.toIntExact(requireIntegral(d, "discountBps")),
                        requireString(d, "membershipId"), requireString(d, "planId"),
                        Math.toIntExact(requireIntegral(d, "planVersion")));
            }
            default -> throw new IllegalStateException("unknown order benefits outcome");
        }
    }

    private static void requireExactKeys(Document d, Set<String> expected) {
        if (!d.keySet().equals(expected)) {
            throw new IllegalStateException("order benefits snapshot has missing or foreign fields for its outcome");
        }
    }

    private static String requireString(Document d, String field) {
        if (!(d.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("order benefits snapshot field missing/invalid: " + field);
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
        throw new IllegalStateException("order benefits snapshot field missing/invalid: " + field);
    }
}
