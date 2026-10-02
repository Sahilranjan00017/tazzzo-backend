package com.tazzzo.customer.order;

import org.bson.Document;

import java.util.Set;

/**
 * The persisted shape of {@link OrderMoneySnapshot}: ONE nested {@code money} document (additive, like {@code benefits}):
 * <pre>
 *   { merchandiseSubtotalPaise, benefitDiscountPaise, payablePaise }
 * </pre>
 * {@code payablePaise} is stored explicitly as the frozen authoritative fact (so nothing ever recomputes it and a later
 * formula change can never alter history) and is VERIFIED against the formula on reconstruction. Reconstruction is
 * STRICT, the same fail-loud convention as every other Order field: a missing/foreign field, a wrong BSON type, an
 * explicit null, a negative amount, a discount above the subtotal or a payable that is not subtotal minus discount
 * throws and is never repaired. The ABSENCE of the whole document is a legacy Order, decided by the caller.
 */
final class OrderMoneySnapshotCodec {

    static final String FIELD = "money";

    private static final Set<String> KEYS = Set.of("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");

    private OrderMoneySnapshotCodec() {
    }

    static Document toDocument(OrderMoneySnapshot s) {
        return new Document("merchandiseSubtotalPaise", s.merchandiseSubtotalPaise())
                .append("benefitDiscountPaise", s.benefitDiscountPaise()).append("payablePaise", s.payablePaise());
    }

    /** {@code raw} is the PRESENT value of the {@code money} field (an explicit null is corruption too). */
    static OrderMoneySnapshot fromDocument(Object raw) {
        if (!(raw instanceof Document d)) {
            throw new IllegalStateException("order money snapshot must be a document");
        }
        if (!d.keySet().equals(KEYS)) {
            throw new IllegalStateException("order money snapshot has missing or foreign fields");
        }
        OrderMoneySnapshot s = new OrderMoneySnapshot(requireIntegral(d, "merchandiseSubtotalPaise"),
                requireIntegral(d, "benefitDiscountPaise"));
        long storedPayable = requireIntegral(d, "payablePaise");
        if (storedPayable != s.payablePaise()) {
            throw new IllegalStateException("order money snapshot payable does not equal subtotal minus discount");
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
        throw new IllegalStateException("order money snapshot field missing/invalid: " + field);
    }
}
