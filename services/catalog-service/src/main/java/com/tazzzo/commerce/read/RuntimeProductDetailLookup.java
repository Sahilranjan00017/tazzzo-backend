package com.tazzzo.commerce.read;

import java.util.Objects;

/**
 * Outcome of one PDP composition (PR-09). Normal business absence is a STATUS, never an
 * exception; infrastructure failure is an EXCEPTION, never a status — an outage must not be
 * representable as "product missing" (frozen failure policy, PR-08 STEP 26 lineage).
 * <ul>
 *   <li>{@code FOUND} — consumer-eligible product composed; {@code detail} present.</li>
 *   <li>{@code NOT_FOUND} — no such product.</li>
 *   <li>{@code INELIGIBLE} — exists but not consumer-eligible. INTERNAL distinction only:
 *       the PR-10 public mapper MUST collapse this to the same flat 404 as NOT_FOUND
 *       (unpublished existence is never leaked, rule L-5).</li>
 * </ul>
 */
public record RuntimeProductDetailLookup(Status status, RuntimeProductDetail detail) {

    public enum Status { FOUND, NOT_FOUND, INELIGIBLE }

    public RuntimeProductDetailLookup {
        Objects.requireNonNull(status, "status required");
        if (status == Status.FOUND && detail == null) {
            throw new IllegalArgumentException("FOUND lookup requires detail");
        }
        if (status != Status.FOUND && detail != null) {
            throw new IllegalArgumentException(status + " lookup must carry no detail");
        }
    }

    public static RuntimeProductDetailLookup found(RuntimeProductDetail detail) {
        return new RuntimeProductDetailLookup(Status.FOUND, detail);
    }

    public static RuntimeProductDetailLookup notFound() {
        return new RuntimeProductDetailLookup(Status.NOT_FOUND, null);
    }

    public static RuntimeProductDetailLookup ineligible() {
        return new RuntimeProductDetailLookup(Status.INELIGIBLE, null);
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }
}
