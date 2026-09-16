package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.catalog.schema.SnapshotTopologyException;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * THE consumer scope-resolution and reachability seam — one implementation for CHILDREN, LIST and
 * PDP (ROOT selects active super-categories by type and needs neither).
 *
 * <p><b>Scope (CONSUMER-ERR-2).</b> The release-bound, consumer-valid vertical set under a node. A
 * release snapshot whose recorded topology is not a tree — a cycle, or a child row without a
 * {@code node_id} — is server-side corruption, not a bad shopper request. The reader fails closed
 * with {@link SnapshotTopologyException}, which extends {@code IllegalStateException}; left alone
 * that reaches the CMS advice's conflict mapping and the public envelope turns it into
 * {@code 400 INVALID_REQUEST} while the measured boundary records {@code unavailable}. Translating
 * it HERE, once, keeps schema corruption out of the transport layer for every route:
 * {@code 503 SERVICE_UNAVAILABLE}, flat ERR-1, outcome {@code unavailable}.
 *
 * <p><b>Reachability (TAX-REACH-1).</b> "The node's own row is active" is not enough: an active
 * vertical beneath a deprecated or merged ancestor would open from a deep link while ROOT hides
 * its branch. A node is consumer-reachable only when every row on its ancestor path is active and
 * the path ends at an active super-category — the same branch ROOT would descend into. Non-reachable
 * is the ordinary, indistinguishable 404 (an ancestor deprecated or merged; a parentless
 * non-super-category such as a holding vertical or an orphan branch). Detectably corrupt ancestry
 * — a cycle, a parent id that names no row in the release, a path deeper than any taxonomy can be —
 * is the CONSUMER-ERR-2 class: 503, never silently repaired.
 *
 * <p>Nothing is repaired or skipped: a corrupt snapshot is never presented as a smaller valid one.
 */
@Component
public class ConsumerTaxonomyScopeResolver {

    /**
     * The taxonomy is four levels deep (super-category → category → sub-category → vertical). A
     * path longer than this is not a longer taxonomy; it is corruption, and it is refused before
     * it can cost more than a handful of reads.
     */
    static final int MAX_ANCESTOR_DEPTH = 16;

    private final SnapshotTaxonomyReader snapshots;

    public ConsumerTaxonomyScopeResolver(SnapshotTaxonomyReader snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * @return the consumer-valid vertical ids under {@code nodeId} in {@code release}, pruning
     *         non-active branches (Phase 5A hardening); empty when the node is absent or pruned
     * @throws ConsumerFailures.Unavailable the snapshot topology under the node is corrupt
     */
    public List<String> scope(String release, String nodeId) {
        try {
            return snapshots.consumerVerticalIdsInSubtree(release, nodeId);
        } catch (SnapshotTopologyException e) {
            throw new ConsumerFailures.Unavailable("snapshot topology corrupt in release " + release);
        }
    }

    /**
     * TAX-REACH-1 for a node the caller has already read from the snapshot (so the row is not
     * fetched twice). {@code null} is simply not reachable.
     *
     * @return true when the node and every ancestor are active and the path ends at an active
     *         super-category; false for absent, non-active, an ancestor that is not active, or a
     *         path that ends without reaching a super-category (a holding vertical, an orphan branch)
     * @throws ConsumerFailures.Unavailable a cycle in the ancestry, a parent id naming no row in
     *         this release, or a path deeper than {@link #MAX_ANCESTOR_DEPTH}
     */
    public boolean isReachable(String release, Document node) {
        Document current = node;
        Set<String> visited = new HashSet<>();
        int depth = 0;
        while (current != null) {
            String id = current.getString("node_id");
            if (id == null || id.isBlank() || !visited.add(id)) {
                throw new ConsumerFailures.Unavailable("snapshot ancestry corrupt (cycle) in release " + release);
            }
            if (++depth > MAX_ANCESTOR_DEPTH) {
                throw new ConsumerFailures.Unavailable("snapshot ancestry corrupt (depth) in release " + release);
            }
            if (!"active".equals(current.getString("status"))) {
                return false;                                  // this row, or an ancestor, is hidden
            }
            if ("super_category".equals(current.getString("node_type"))) {
                return true;                                   // an active root: the branch ROOT shows
            }
            Object parentId = current.get("parent_id");
            if (!(parentId instanceof String p) || p.isBlank()) {
                return false;                                  // unattached: no super-category above it
            }
            Document parent = snapshots.node(release, p);
            if (parent == null) {
                throw new ConsumerFailures.Unavailable("snapshot ancestry corrupt (dangling parent) in release " + release);
            }
            current = parent;
        }
        return false;
    }

    /** {@link #isReachable(String, Document)} for a node id; absent is not reachable. */
    public boolean isReachable(String release, String nodeId) {
        return isReachable(release, snapshots.node(release, nodeId));
    }
}
