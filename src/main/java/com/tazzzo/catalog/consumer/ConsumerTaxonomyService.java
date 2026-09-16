package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Scope resolution, admission and the existence probe are the shared seams
 * {@link ConsumerTaxonomyScopeResolver}, {@link ConsumerAdmissionGate} and
 * {@link ConsumerVisibilityProbe} — one implementation each for ROOT, CHILDREN and LIST.
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
    private final ConsumerTaxonomyScopeResolver scopes;
    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerVisibilityProbe probe;

    public ConsumerTaxonomyService(SnapshotTaxonomyReader snapshots,
                                   ConsumerTaxonomyScopeResolver scopes,
                                   ConsumerReleaseResolver releases,
                                   ConsumerAdmissionGate gate,
                                   ConsumerVisibilityProbe probe) {
        this.snapshots = snapshots;
        this.scopes = scopes;
        this.releases = releases;
        this.gate = gate;
        this.probe = probe;
    }

    /**
     * ROOT-1. Request outcome and latency are NOT measured here — the controller owns that clock
     * (Q5-OBS-1), so that success, not_found, rate_limited and unavailable all share ONE boundary
     * that includes client-identity resolution. This class records only what it alone knows: the
     * charged cost, the admission observation, and each probe.
     */
    public ConsumerDtos.NodeListResponse root(String explicitRelease, ConsumerIdentity identity) {
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

        // CONSUMER-VALID descendants from THIS release's snapshot, before any charge, because the
        // cost is computed from them. Status-aware on purpose: ConsumerEligibility knows nothing
        // about taxonomy status and trusts this set, so a product under a vertical that is
        // deprecated or merged in the snapshot must not be able to make a root appear. This reads
        // immutable data (TR-5); nothing live is touched yet.
        Map<Document, List<String>> descendants = new LinkedHashMap<>();
        long units = 1;
        for (Document candidate : candidates) {
            List<String> verticals = scopes.scope(release, candidate.getString("node_id"));
            descendants.put(candidate, verticals);
            units += verticals.size();
        }
        gate.charge(ConsumerObservability.Route.ROOT, identity, units);

        List<Document> visible = new ArrayList<>();
        for (Map.Entry<Document, List<String>> entry : descendants.entrySet()) {
            if (probe.hasEligibleProduct(ConsumerObservability.Route.ROOT,
                    ConsumerObservability.ProbeScope.CHILD, entry.getValue())) {
                visible.add(entry.getKey());
            }
        }
        return new ConsumerDtos.NodeListResponse(release, project(visible));
    }

    /**
     * CHILD-1 — immediate consumer-visible children of ANY taxonomy node, release-bound.
     *
     * <p>Order of operations is the contract (CHILD-Q5-1):
     * <pre>
     *   resolve release ONCE
     *   requested node from the snapshot     absent, non-active or NOT REACHABLE (TAX-REACH-1:
     *                                        an inactive ancestor, no super-category above it)
     *                                        -> charge 1 -> 404, ZERO probes
     *   parentScope + one childScope per ACTIVE immediate candidate, from the consumer-valid walk
     *   cost = 1 + |parentScope| + Σ|childScope|
     *   CHARGE Q5                            before any products query
     *   PARENT probe first                   miss -> 404 and NO child probes; error -> 503
     *   one probe per candidate              hit visible · miss hidden · error -> 503
     *   TR-3 order -> CAT-NODE-1
     * </pre>
     *
     * <p><b>The requested node gets its OWN probe.</b> Its visibility is never inferred from
     * whether any child turned out visible: a visible vertical has no children and must return
     * {@code 200 items=[]}, and an active non-leaf is a valid scope even when every immediate
     * child is filtered out by current consumer state.
     *
     * <p><b>A missing or non-active node still costs 1, charged before the 404</b>, so probing the
     * tree for what exists is never free.
     */
    public ConsumerDtos.NodeListResponse children(String nodeId, String explicitRelease,
                                                  ConsumerIdentity identity) {
        String release = releases.resolve(explicitRelease);

        Document requested = snapshots.node(release, nodeId);
        // TAX-REACH-1: the node AND its whole ancestor path must be active up to a super-category
        // -- the branch ROOT would show. Absent, non-active and non-reachable are one answer (L-5),
        // the same as consumer-empty below; corrupt ancestry is a 503 raised inside the seam.
        if (!scopes.isReachable(release, requested)) {
            gate.charge(ConsumerObservability.Route.CHILDREN, identity, 1);
            throw new ConsumerFailures.NotFound("node not consumer-reachable: " + nodeId);
        }

        List<String> parentScope = scopes.scope(release, nodeId);
        Map<Document, List<String>> childScopes = new LinkedHashMap<>();
        long units = 1 + parentScope.size();
        for (Document candidate : snapshots.immediateChildren(release, nodeId)) {
            if (!"active".equals(candidate.getString("status"))) {
                continue;
            }
            List<String> scope = scopes.scope(release, candidate.getString("node_id"));
            childScopes.put(candidate, scope);
            units += scope.size();
        }
        gate.charge(ConsumerObservability.Route.CHILDREN, identity, units);

        // The parent's own visibility, FIRST. A hidden scope answers 404 without touching a child.
        if (!probe.hasEligibleProduct(ConsumerObservability.Route.CHILDREN,
                ConsumerObservability.ProbeScope.PARENT, parentScope)) {
            throw new ConsumerFailures.NotFound("node consumer-empty: " + nodeId);
        }

        List<Document> visible = new ArrayList<>();
        for (Map.Entry<Document, List<String>> entry : childScopes.entrySet()) {
            if (probe.hasEligibleProduct(ConsumerObservability.Route.CHILDREN,
                    ConsumerObservability.ProbeScope.CHILD, entry.getValue())) {
                visible.add(entry.getKey());
            }
        }
        return new ConsumerDtos.NodeListResponse(release, project(visible));
    }

    /** TR-3 transport order, then CAT-NODE-1 projection: id and name, nothing else. */
    private static List<ConsumerDtos.ConsumerNode> project(List<Document> nodes) {
        nodes.sort(TransportOrder.by(n -> n.getString("name"), n -> n.getString("node_id")));
        List<ConsumerDtos.ConsumerNode> items = new ArrayList<>(nodes.size());
        for (Document node : nodes) {
            items.add(new ConsumerDtos.ConsumerNode(node.getString("node_id"), node.getString("name")));
        }
        return List.copyOf(items);
    }
}
