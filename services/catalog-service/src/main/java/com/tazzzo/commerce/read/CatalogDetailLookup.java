package com.tazzzo.commerce.read;

import java.util.Objects;

/**
 * Result of a Catalog detail read (PR-09). Three states the composer treats differently:
 * <ul>
 *   <li>{@code FOUND} — a consumer-eligible product exists; {@code facts} carries it.</li>
 *   <li>{@code NOT_FOUND} — no product document exists for the id.</li>
 *   <li>{@code INELIGIBLE} — a document exists but fails {@code ConsumerEligibility}
 *       (draft/discontinued/merged lifecycle, unconfirmed classification, holding vertical,
 *       non-admitted type). Kept distinct INTERNALLY for observability; the public layer
 *       (PR-10) must collapse it into the same flat 404 as NOT_FOUND — unpublished existence
 *       is never leaked.</li>
 * </ul>
 * Status-shape invariants follow the proven {@code InventoryLookup} pattern (PR-08 review):
 * an inconsistent pairing is unrepresentable.
 */
public record CatalogDetailLookup(Status status, CatalogProductDetailFacts facts) {

    public enum Status { FOUND, NOT_FOUND, INELIGIBLE }

    public CatalogDetailLookup {
        Objects.requireNonNull(status, "status required");
        if (status == Status.FOUND && facts == null) {
            throw new IllegalArgumentException("FOUND lookup requires facts");
        }
        if (status != Status.FOUND && facts != null) {
            throw new IllegalArgumentException(status + " lookup must carry no facts");
        }
    }

    public static CatalogDetailLookup found(CatalogProductDetailFacts facts) {
        return new CatalogDetailLookup(Status.FOUND, facts);
    }

    public static CatalogDetailLookup notFound() {
        return new CatalogDetailLookup(Status.NOT_FOUND, null);
    }

    public static CatalogDetailLookup ineligible() {
        return new CatalogDetailLookup(Status.INELIGIBLE, null);
    }
}
