package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ROOT-1 — the consumer taxonomy root, release-bound, hide-empty, and rate-limited BEFORE it
 * touches product data.
 *
 * <p>The order of operations is the contract, not an implementation detail:
 * <pre>
 *   resolve the release ONCE            TR-2 / TR2-CURRENT-1
 *   candidates = super-categories        ROOT-1 §1.1 — NOT "parentless nodes"
 *   descendants from THAT snapshot       TR-5, release-bound
 *   cost = 1 + SUM(descendant counts)    Q5-f, computed — never a constant
 *   CHARGE Q5                            before any live product read
 *   one existence probe per candidate    TR-4B
 *   hide the consumer-empty              TR-4A
 *   order, then project                  TR-3, CAT-NODE-1
 * </pre>
 *
 * <p><b>Q5 is charged before the probes, and that ordering is the point.</b> Charging afterwards
 * would let a rejected request do the exact work the limit exists to prevent — the probes are the
 * expensive part, and at launch, when most verticals are consumer-empty, they are at their most
 * expensive because {@code limit(1)} only short-circuits on a match.
 */
@Service
public class ConsumerTaxonomyService {

    static final String SUPER_CATEGORY = "super_category";

    private final SnapshotTaxonomyReader snapshots;
    private final ConsumerReleaseResolver releases;
    private final MongoDatabase db;
    private final ObjectProvider<ConsumerRateLimiter> limiter;

    public ConsumerTaxonomyService(SnapshotTaxonomyReader snapshots,
                                   ConsumerReleaseResolver releases,
                                   MongoDatabase db,
                                   ObjectProvider<ConsumerRateLimiter> limiter) {
        this.snapshots = snapshots;
        this.releases = releases;
        this.db = db;
        this.limiter = limiter;
    }

    public ConsumerDtos.RootResponse root(String explicitRelease, String clientIp,
                                          Optional<String> installationId) {
        String release = releases.resolve(explicitRelease);

        // Candidates are selected by TYPE. "parent_id == null" would return NINE nodes in the
        // seeded tree — the seven super-categories AND both holding verticals, which are
        // parentless roots — leaving Q3 as the only thing between a shopper and TZV-SCOPE-BLOCKED.
        List<Document> candidates = new ArrayList<>();
        for (Document node : snapshots.nodesOfType(release, SUPER_CATEGORY)) {
            if ("active".equals(node.getString("status"))) {
                candidates.add(node);
            }
        }

        // Descendants resolved from THIS release's snapshot, before any charge, because the cost
        // is computed from them. This reads immutable data (TR-5); nothing live is touched yet.
        Map<Document, List<String>> descendants = new LinkedHashMap<>();
        long units = 1;
        for (Document candidate : candidates) {
            List<String> verticals = snapshots.verticalIdsInSubtree(release, candidate.getString("node_id"));
            descendants.put(candidate, verticals);
            units += verticals.size();
        }
        charge(clientIp, installationId, units);

        List<Document> visible = new ArrayList<>();
        for (Map.Entry<Document, List<String>> entry : descendants.entrySet()) {
            if (hasEligibleProduct(entry.getValue())) {
                visible.add(entry.getKey());
            }
        }

        visible.sort(TransportOrder.by(n -> n.getString("name"), n -> n.getString("node_id")));
        List<ConsumerDtos.ConsumerNode> items = new ArrayList<>(visible.size());
        for (Document node : visible) {
            items.add(new ConsumerDtos.ConsumerNode(node.getString("node_id"), node.getString("name")));
        }
        return new ConsumerDtos.RootResponse(release, List.copyOf(items));
    }

    /**
     * Q5-f: the weight is COMPUTED from the resolved snapshot, never a constant. The 294 units a
     * root listing costs on the seeded tree is an OBSERVATION of that tree, not the formula.
     */
    private void charge(String clientIp, Optional<String> installationId, long units) {
        ConsumerRateLimiter rateLimiter = limiter.getIfAvailable();
        if (rateLimiter == null) {
            // DISABLED mode builds no limiter. That is the fail-closed state, not "unlimited":
            // the surface must not serve while it cannot be limited (Q4-d).
            throw new ConsumerFailures.Unavailable("consumer rate limiting is not configured");
        }
        int cost = (int) Math.min(units, Integer.MAX_VALUE);
        Admission admission = rateLimiter.admit(clientIp, installationId, cost);
        if (admission instanceof Admission.RateLimited limited) {
            throw new ConsumerFailures.RateLimited(limited.retryAfter());
        }
        if (admission instanceof Admission.Unavailable unavailable) {
            throw new ConsumerFailures.Unavailable("limiter: " + unavailable.reason());
        }
    }

    /**
     * TR-4B: ONE indexed existence probe per candidate, against CURRENT membership. An empty
     * descendant set matches nothing, which is correct — no consumer-visible descendant means no
     * eligible product.
     */
    private boolean hasEligibleProduct(List<String> verticalIds) {
        try {
            return db.getCollection("products")
                    .find(ConsumerEligibility.within(verticalIds))
                    .projection(new Document("_id", 1))
                    .limit(1)
                    .first() != null;
        } catch (RuntimeException e) {
            // TR-4B failure semantics: a timeout is NOT evidence that a node is empty. Neither
            // "show it" nor "hide it" is available — the state is UNKNOWN, so the whole request
            // fails rather than manufacturing a child set.
            throw new ConsumerFailures.Unavailable("visibility probe failed");
        }
    }
}
