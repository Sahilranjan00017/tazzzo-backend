package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RELEASE-BOUND taxonomy topology, read from {@code taxonomy_snapshot_nodes} and nothing else.
 *
 * <pre>
 *   TaxonomyService          taxonomy_nodes            LIVE topology     CMS / governance
 *   SnapshotTaxonomyReader   taxonomy_snapshot_nodes   RELEASE-BOUND     consumer reads
 *
 *   NO fallback in either direction.
 * </pre>
 *
 * <p>The snapshot is authoritative (TR-5). A node that exists in the live tree today but has no
 * {@code (release_id, node_id)} row is ABSENT from that release — this reader never consults
 * {@code taxonomy_nodes} to "fill in" what a snapshot lacks, and it never reads a different
 * release to answer a question about the requested one. Every query in this class binds
 * {@code release_id} through {@link #inRelease}, so an unscoped read cannot be written by accident.
 *
 * <p>Every field returned — {@code name}, {@code parent_id}, {@code node_type}, {@code status} —
 * is the value recorded IN THE SNAPSHOT, never today's live value. A later phase that needs Q3's
 * status filtering must use the status recorded here, not the live node.
 *
 * <p>This is TOPOLOGY ONLY. It does not implement consumer eligibility, TR-4A hide-empty, TR-4B
 * product probes, TR-3 presentation ordering, Q5, ERR-1, cursor logic, HTTP transport, or any
 * "current release" resolution: a concrete {@code releaseId} is required on every call. Nothing
 * here touches {@code products}, {@code offers_current}, {@code consumer_projection_policy} or
 * {@code classification.release_id}.
 *
 * <p>Consumes the row shape {@code TaxonomyChangeService.activateRelease} already persists — a copy
 * of the live node with {@code _id} replaced by {@code release_id} + {@code node_id}. It does not
 * redesign it.
 */
@Component
public class SnapshotTaxonomyReader {

    static final String SNAPSHOT_COLLECTION = "taxonomy_snapshot_nodes";

    private final MongoDatabase db;

    public SnapshotTaxonomyReader(MongoDatabase db) {
        this.db = db;
    }

    /** The node as recorded in the requested release, or null when that release has no such row. */
    public Document node(String releaseId, String nodeId) {
        requireRelease(releaseId);
        if (nodeId == null) {
            return null;
        }
        return db.getCollection(SNAPSHOT_COLLECTION)
                .find(inRelease(releaseId, Filters.eq("node_id", nodeId)))
                .first();
    }

    /**
     * The nodes whose SNAPSHOT {@code parent_id} is {@code parentNodeId}, in the requested release.
     * Unordered: presentation ordering (TR-3) is deliberately not this component's concern.
     */
    public List<Document> immediateChildren(String releaseId, String parentNodeId) {
        requireRelease(releaseId);
        if (parentNodeId == null) {
            return List.of();
        }
        return db.getCollection(SNAPSHOT_COLLECTION)
                .find(inRelease(releaseId, Filters.eq("parent_id", parentNodeId)))
                .into(new ArrayList<>());
    }

    /**
     * Vertical ids in the subtree rooted at {@code nodeId}, as recorded in the requested release.
     *
     * <pre>
     *   node is a vertical                    -> that vertical itself
     *   node is any non-leaf                  -> the vertical descendants beneath it
     *   node absent from THIS snapshot        -> empty
     *   never another release, never live
     * </pre>
     *
     * <p>Self-inclusion for a vertical is the topology primitive LIST-1 L-1 already requires — a
     * vertical scope resolves to itself — not a new decision made here.
     *
     * <p>Walks one level per query rather than one query per node; the tree is four levels deep, so
     * a super-category resolves in at most three round trips, every one of them release-bound.
     */
    public List<String> verticalIdsInSubtree(String releaseId, String nodeId) {
        Document root = node(releaseId, nodeId);
        if (root == null) {
            return List.of();
        }
        if ("vertical".equals(root.getString("node_type"))) {
            return List.of(nodeId);
        }
        List<String> verticals = new ArrayList<>();
        List<String> frontier = List.of(nodeId);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (Document child : db.getCollection(SNAPSHOT_COLLECTION)
                    .find(inRelease(releaseId, Filters.in("parent_id", frontier)))
                    .into(new ArrayList<>())) {
                if ("vertical".equals(child.getString("node_type"))) {
                    verticals.add(child.getString("node_id"));
                } else {
                    next.add(child.getString("node_id"));
                }
            }
            frontier = next;
        }
        return List.copyOf(verticals);
    }

    /**
     * The ONLY way a query leaves this class. Binding {@code release_id} here, by construction,
     * is what makes "never crosses a release" mechanically visible in review rather than a
     * discipline each call site must remember.
     */
    private static Bson inRelease(String releaseId, Bson condition) {
        return Filters.and(Filters.eq("release_id", releaseId), condition);
    }

    /** A concrete release on every call. "Current" resolution belongs to a later phase, not here. */
    private static void requireRelease(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) {
            throw new IllegalArgumentException("a concrete releaseId is required");
        }
    }
}
