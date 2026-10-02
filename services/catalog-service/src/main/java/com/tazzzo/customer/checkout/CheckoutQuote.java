package com.tazzzo.customer.checkout;

import com.tazzzo.customer.address.AddressId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-13A — the immutable checkout quote: the validated snapshot at {@code createdAt}. It is NOT a
 * reservation of stock and NOT a permanent price lock; it is only valid until {@code expiresAt} and
 * a future Order must revalidate stock and quote validity. Carries no internal routing identity.
 *
 * <p>Hardening review — a future Order trusts a loaded quote without re-deriving these facts, so the
 * compact constructor validates every invariant a caller (including {@link CheckoutQuoteRepository}
 * reconstructing from Mongo) relies on. A violation means corrupt/hand-edited data or a programming
 * defect — never normalized or silently repaired; it fails LOUD ({@link IllegalArgumentException} or
 * {@link ArithmeticException}), the same convention as {@code CatalogCardFacts}/
 * {@code ProductCardBaseProjection}/{@code AddressId}/{@code CheckoutQuoteId} elsewhere in this
 * codebase. int64 paise only; totals are cross-checked with exact ({@code Math.*Exact}) arithmetic,
 * never trusted as independently-stored numbers.
 *
 * <p>PR-13B — {@code addressVersion} is INTERNAL provenance only (never in {@link CheckoutQuoteDto}
 * or the public OpenAPI response): the exact version of {@code addressId} that commerce validation
 * actually ran against, captured from {@code CheckoutService.ValidatedAddress} (never re-read, never
 * client-supplied), so a future Order can prove the saved address has not changed since this quote
 * was validated. This is exactly why it is validated here too — a future Order trusts it unseen.
 */
public record CheckoutQuote(String quoteId, long cartVersion, String addressId, long addressVersion,
                            List<Line> lines, int itemCount, long subtotalPaise, String currency,
                            Instant createdAt, Instant expiresAt, CheckoutBenefitSnapshot benefitSnapshot) {

    /**
     * A quote WITHOUT a Benefits snapshot: a legacy (pre-Checkout-Benefits) quote, or a test fixture. Production code
     * that CREATES a quote never calls this (ArchUnit-enforced): every new quote carries a snapshot.
     */
    public CheckoutQuote(String quoteId, long cartVersion, String addressId, long addressVersion, List<Line> lines,
                         int itemCount, long subtotalPaise, String currency, Instant createdAt, Instant expiresAt) {
        this(quoteId, cartVersion, addressId, addressVersion, lines, itemCount, subtotalPaise, currency, createdAt,
                expiresAt, null);
    }

    public record Line(String skuId, int quantity, long unitPricePaise, long lineTotalPaise) {
        public Line {
            if (skuId == null || skuId.isBlank()) {
                throw new IllegalArgumentException("skuId required");
            }
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be >= 1: " + quantity);
            }
            if (unitPricePaise < 0) {
                throw new IllegalArgumentException("unitPricePaise must be >= 0: " + unitPricePaise);
            }
            if (lineTotalPaise < 0) {
                throw new IllegalArgumentException("lineTotalPaise must be >= 0: " + lineTotalPaise);
            }
            if (lineTotalPaise != Math.multiplyExact(unitPricePaise, (long) quantity)) {
                throw new IllegalArgumentException("lineTotalPaise does not equal unitPricePaise * quantity for "
                        + skuId);
            }
        }
    }

    public CheckoutQuote {
        if (!CheckoutQuoteId.isValid(quoteId)) {
            throw new IllegalArgumentException("invalid quoteId shape");
        }
        if (cartVersion < 0) {
            throw new IllegalArgumentException("cartVersion must be >= 0: " + cartVersion);
        }
        if (addressId == null) {
            throw new IllegalArgumentException("addressId required");
        }
        new AddressId(addressId); // throws IllegalArgumentException on an invalid shape
        if (addressVersion < 0) {
            throw new IllegalArgumentException("addressVersion must be >= 0: " + addressVersion);
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must be non-empty");
        }
        Set<String> seen = new HashSet<>();
        long computedSubtotal = 0;
        int computedItemCount = 0;
        for (Line l : lines) {
            if (!seen.add(l.skuId())) {
                throw new IllegalArgumentException("duplicate skuId in quote: " + l.skuId());
            }
            computedSubtotal = Math.addExact(computedSubtotal, l.lineTotalPaise());
            computedItemCount = Math.addExact(computedItemCount, l.quantity());
        }
        lines = List.copyOf(lines);
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be >= 1: " + itemCount);
        }
        if (itemCount != computedItemCount) {
            throw new IllegalArgumentException("itemCount does not equal the sum of line quantities");
        }
        if (subtotalPaise < 0) {
            throw new IllegalArgumentException("subtotalPaise must be >= 0: " + subtotalPaise);
        }
        if (subtotalPaise != computedSubtotal) {
            throw new IllegalArgumentException("subtotalPaise does not equal the sum of line totals");
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("currency must be INR");
        }
        if (createdAt == null || expiresAt == null) {
            throw new IllegalArgumentException("createdAt/expiresAt required");
        }
        if (!createdAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("createdAt must be before expiresAt");
        }
        // null ONLY for a legacy quote (never "no benefit"); when present it is about THIS quote's subtotal
        if (benefitSnapshot != null && benefitSnapshot.eligibleSubtotalPaise() != subtotalPaise) {
            throw new IllegalArgumentException("benefit snapshot eligibleSubtotalPaise does not equal the quote subtotal");
        }
    }

    /** The public-safe projection of the stored advisory snapshot; EMPTY for a legacy quote (never "not applied"). */
    java.util.Optional<CheckoutBenefitPreview> benefitPreview() {
        return benefitSnapshot == null ? java.util.Optional.empty()
                : java.util.Optional.of(CheckoutBenefitPreview.from(benefitSnapshot));
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
