package com.tazzzo.commerce.read;

/**
 * A PDP composition could not produce a truthful internal detail because of a DATA-QUALITY /
 * inconsistent-snapshot condition (PR-09 review, HIGH). This is NOT a business absence
 * (NOT_FOUND/INELIGIBLE) and NOT an ordinary infrastructure outage — it is a typed signal that
 * authoritative inputs are internally inconsistent or exceed a technical representation bound, so
 * the composer must fail rather than alter or fabricate product truth.
 *
 * <p>Raised when:
 * <ul>
 *   <li>an ELIGIBLE product's Catalog facts violate the {@link ProductCardBaseProjection}
 *       technical bounds (e.g. an over-long title) — clamping authoritative Catalog data merely
 *       to fit a CARD projection is forbidden; and</li>
 *   <li>an existing base row's {@code catalogVersion} is AHEAD of the freshly read Catalog version
 *       — an impossible/suspicious snapshot that must never be served as if current.</li>
 * </ul>
 *
 * <p>Propagated typed and untouched; the future PR-10 API layer may map it to 503.
 */
public class ProductDetailCompositionException extends RuntimeException {
    public ProductDetailCompositionException(String message) {
        super(message);
    }

    public ProductDetailCompositionException(String message, Throwable cause) {
        super(message, cause);
    }
}
