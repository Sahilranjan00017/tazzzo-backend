package com.tazzzo.catalog.consumer;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Collection;
import java.util.Set;

/**
 * THE consumer-eligibility predicate. There is exactly one definition, and every consumer-plane
 * read resolves to it: TR-4A visibility, TR-4B emptiness probes, LIST-1 listings, PDP-1 detail and
 * the standing LAUNCH-CENSUS. {@code ConsumerPredicateGuardIT} enforces that structurally — a
 * second, "almost equivalent" copy is the failure this class exists to make impossible, because
 * two filters that agree today drift silently and both keep returning plausible answers.
 *
 * <p>Provenance of each axis — all ratified, none invented here:
 * <pre>
 *   lifecycle == active                      Q3 (2026-09-04)
 *   classification.status == confirmed       Q3 — "provisional may never be promoted as final"
 *   vertical NOT a holding vertical          Q3 / CR-11 — a holding vertical IS a work queue
 *   product_type IN {single, variant_pack}   LIST-ELIG-1 (2026-09-06), via LIST-1 L-4
 * </pre>
 *
 * <p>Q3's fourth axis — "classified under the release the query is scoped to" — is NOT a field test
 * here. REL-MEM-1 (R-A, 2026-09-06) ratified that {@code classification.release_id} is provenance
 * only and is never consulted for membership: the resolved release scopes the TAXONOMY, and the
 * caller applies it by passing the vertical set resolved from that release's snapshot to
 * {@link #within(Collection)}. Membership itself is always CURRENT.
 *
 * <p>Deliberately conjunctive and deliberately narrow. Under CAT-SEC-1/Q2 this predicate may be the
 * entire security boundary for discovery, so it is widened only by explicit decision, never by
 * omission. {@code canonical_key} has no visibility effect in either direction (X-1).
 *
 * <p>NOT part of this predicate: {@code offers_current.available}. Stock and offer availability are
 * a different contract (traversal §5.3.1) — "available" is not a synonym for "eligible", and the
 * field exists under that exact name.
 */
public final class ConsumerEligibility {

    /** The only lifecycle a shopper may see. */
    public static final String LIFECYCLE = "active";

    /** The only classification status a shopper may see. */
    public static final String CLASSIFICATION_STATUS = "confirmed";

    /** Holding verticals are work queues, never storefront categories (CR-11). */
    public static final Set<String> HOLDING_VERTICALS =
            Set.of(MintService.UNCLASSIFIED, MintService.SCOPE_BLOCKED);

    /** LIST-ELIG-1. Bundles are excluded from v1 consumer discovery; placement is deferred. */
    public static final Set<String> ADMITTED_PRODUCT_TYPES = Set.of("single", "variant_pack");

    private ConsumerEligibility() {
    }

    /**
     * The predicate, unscoped by taxonomy. Callers that already constrain the vertical should use
     * {@link #within(Collection)} so the compound index prefix is used.
     */
    public static Bson filter() {
        return Filters.and(
                Filters.eq("lifecycle", LIFECYCLE),
                Filters.eq("classification.status", CLASSIFICATION_STATUS),
                Filters.in("product_type", ADMITTED_PRODUCT_TYPES),
                Filters.nin("classification.vertical_id", HOLDING_VERTICALS));
    }

    /**
     * The predicate scoped to a vertical set the caller resolved from the requested release
     * snapshot (TR-5). An EMPTY set matches nothing — that is correct, not a degenerate case: no
     * consumer-visible descendant means no eligible product.
     *
     * <p>The holding-vertical exclusion is retained even though a release-resolved set should never
     * contain one. Law 4: never rely on a single guard.
     */
    public static Bson within(Collection<String> verticalIds) {
        return Filters.and(
                Filters.in("classification.vertical_id", verticalIds),
                filter());
    }

    /**
     * In-memory evaluation of a fetched product document, for PDP-1 and any caller holding the
     * document rather than issuing a query. Same axes, same order, no second definition of truth.
     */
    public static boolean isEligible(Document product) {
        if (product == null) {
            return false;
        }
        if (!LIFECYCLE.equals(product.getString("lifecycle"))) {
            return false;
        }
        if (!ADMITTED_PRODUCT_TYPES.contains(product.getString("product_type"))) {
            return false;
        }
        Document classification = product.get("classification", Document.class);
        if (classification == null) {
            return false;
        }
        if (!CLASSIFICATION_STATUS.equals(classification.getString("status"))) {
            return false;
        }
        String verticalId = classification.getString("vertical_id");
        return verticalId != null && !HOLDING_VERTICALS.contains(verticalId);
    }
}
