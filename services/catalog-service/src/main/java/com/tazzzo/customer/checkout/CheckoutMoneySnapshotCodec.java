package com.tazzzo.customer.checkout;

import org.bson.Document;

import java.util.Set;

/**
 * The persisted shape of {@link CheckoutMoneySnapshot}: ONE nested {@code money} document on the quote.
 * <pre>
 *   { merchandiseSubtotalPaise, benefitDiscountPaise, payablePaise }
 * </pre>
 * {@code payablePaise} is stored explicitly as the frozen advisory fact, but is never trusted: reconstruction rebuilds
 * the snapshot from the two components and requires the stored payable to equal the derived one. STRICT (the Checkout
 * convention: nothing is defaulted or repaired): a missing/foreign field, a wrong BSON type, an explicit null or a
 * violated invariant throws. The ABSENCE of the whole field is a legacy quote and is decided by the caller. Corruption
 * is mapped by the public boundary to a safe 500 {@code INTERNAL}, like every other corrupt quote field.
 */
final class CheckoutMoneySnapshotCodec {

    static final String FIELD = "money";

    private static final Set<String> KEYS = Set.of("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");

    private CheckoutMoneySnapshotCodec() {
    }

    static Document toDocument(CheckoutMoneySnapshot s) {
        return new Document("merchandiseSubtotalPaise", s.merchandiseSubtotalPaise())
                .append("benefitDiscountPaise", s.benefitDiscountPaise()).append("payablePaise", s.payablePaise());
    }

    /** {@code raw} is the PRESENT value of the {@code money} field (an explicit null is corruption too). */
    static CheckoutMoneySnapshot fromDocument(Object raw) {
        if (!(raw instanceof Document d)) {
            throw new IllegalArgumentException("checkout quote money snapshot must be a document");
        }
        if (!d.keySet().equals(KEYS)) {
            throw new IllegalArgumentException("checkout quote money snapshot has missing or foreign fields");
        }
        CheckoutMoneySnapshot s = new CheckoutMoneySnapshot(requireIntegral(d, "merchandiseSubtotalPaise"),
                requireIntegral(d, "benefitDiscountPaise"));
        if (requireIntegral(d, "payablePaise") != s.payablePaise()) {
            throw new IllegalArgumentException(
                    "checkout quote money snapshot payable does not equal subtotal minus discount");
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
        throw new IllegalArgumentException("checkout quote money snapshot field missing/invalid: " + field);
    }
}
