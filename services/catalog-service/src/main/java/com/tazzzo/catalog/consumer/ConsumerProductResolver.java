package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The SINGLE reusable core of consumer product resolution (PDP-1 §2.3 / PDP-MERGE-1 / L-5),
 * extracted so there is exactly ONE meaning of "resolve a product id to its consumer-facing
 * survivor". Both the public legacy PDP ({@link ConsumerProductDetailService}) and the internal
 * commerce PDP composer (via {@code CatalogProductDetailReader}) depend on THIS — neither may
 * fork the merge walk, the bound, the cycle rule, or the eligibility predicate.
 *
 * <p>Responsibilities (and ONLY these — release resolution, admission/rate-limiting, consumer
 * identity, release-scoped reachability and the response envelope belong to the CALLER):
 * <ol>
 *   <li>fetch the product by {@code _id}, narrowly projected (no probe, no scan);</li>
 *   <li>follow {@code lifecycle == merged} through {@code merged_into} to the survivor — a
 *       visited set, at most {@link #MAX_MERGE_HOPS} hops; a cycle, a missing target, a
 *       malformed/blank pointer or an exceeded bound is CORRUPT catalogue state, surfaced as
 *       {@link ConsumerFailures.Unavailable} (the write path cannot mint these — a merged product
 *       is terminal), never a redirect and never a business "not found";</li>
 *   <li>judge the survivor with the ONE ratified {@link ConsumerEligibility} predicate.</li>
 * </ol>
 *
 * <p>Returns the survivor {@link Document} for FOUND (projection and reachability are applied by
 * the caller, in the caller's order); NOT_FOUND / INELIGIBLE for the two flat-404 business
 * absences. Corruption throws. This class does NOT project attributes and does NOT evaluate
 * reachability — those stay caller-owned so the legacy service keeps its exact ordering
 * (reachability BEFORE projection, PDP-1 step 6 then 7).
 *
 * <p>{@code version} is projected in addition to the legacy PDP field set purely so an internal
 * reuse can compare the survivor's Catalog version against a derived projection's snapshot; it is
 * a single scalar, the read stays narrow, and it never enters the public response.
 */
@Component
public class ConsumerProductResolver {

    /** PDP-MERGE-1: a valid longer chain must not be rejected; beyond this is corrupt state. */
    public static final int MAX_MERGE_HOPS = 32;

    /**
     * Exactly what the merge walk, the predicate, the projector and the internal freshness guard
     * read; nothing internal beyond {@code version}. {@code classification} is projected by
     * sub-field so provenance (release_id, confidence, evidence) never enters memory.
     */
    static final List<String> PDP_FIELDS = List.of(
            "_id", "title", "brand_code", "product_type", "lifecycle",
            "classification.status", "classification.vertical_id", "attributes", "merged_into", "version");

    private final MongoDatabase db;

    public ConsumerProductResolver(MongoDatabase db) {
        this.db = Objects.requireNonNull(db);
    }

    public Resolution resolve(String productId) {
        Document product = fetch(productId);
        if (product == null) {
            return Resolution.notFound();
        }
        // Follow the merge chain to the survivor. The visited set seeds with the requested id so a
        // self-merge is caught, and the bound is on HOPS, not on set size.
        Set<String> visited = new HashSet<>();
        visited.add(productId);
        int hops = 0;
        while ("merged".equals(product.getString("lifecycle"))) {
            Object target = product.get("merged_into");
            if (!(target instanceof String next) || next.isBlank()) {
                throw new ConsumerFailures.Unavailable("merge pointer corrupt");
            }
            if (!visited.add(next)) {
                throw new ConsumerFailures.Unavailable("merge chain cyclic");
            }
            if (++hops > MAX_MERGE_HOPS) {
                throw new ConsumerFailures.Unavailable("merge chain exceeds " + MAX_MERGE_HOPS + " hops");
            }
            product = fetch(next);
            if (product == null) {
                throw new ConsumerFailures.Unavailable("merge target missing");
            }
        }
        if (!ConsumerEligibility.isEligible(product)) {
            return Resolution.ineligible();
        }
        return Resolution.found(product);
    }

    /** One point read by primary key, narrowly projected. Never a probe, never a scan. */
    private Document fetch(String productId) {
        return db.getCollection("products")
                .find(Filters.eq("_id", productId))
                .projection(Projections.include(PDP_FIELDS))
                .first();
    }

    /**
     * Outcome of resolution. FOUND carries the survivor document (projected); the two absences
     * carry none. Corruption is NEVER a status — it is a thrown {@link ConsumerFailures.Unavailable}.
     */
    public record Resolution(Status status, Document survivor) {

        public enum Status { FOUND, NOT_FOUND, INELIGIBLE }

        public Resolution {
            Objects.requireNonNull(status, "status required");
            if (status == Status.FOUND && survivor == null) {
                throw new IllegalArgumentException("FOUND resolution requires a survivor document");
            }
            if (status != Status.FOUND && survivor != null) {
                throw new IllegalArgumentException(status + " resolution must carry no document");
            }
        }

        public static Resolution found(Document survivor) {
            return new Resolution(Status.FOUND, survivor);
        }

        public static Resolution notFound() {
            return new Resolution(Status.NOT_FOUND, null);
        }

        public static Resolution ineligible() {
            return new Resolution(Status.INELIGIBLE, null);
        }
    }
}
