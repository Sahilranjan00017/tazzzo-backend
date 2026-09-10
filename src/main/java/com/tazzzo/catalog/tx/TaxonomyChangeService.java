package com.tazzzo.catalog.tx;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.ReleaseGate;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Step 1 — the taxonomy change machinery (v1.1 spec Parts 3/20). The frozen v0.9.0 tree is
 * NEVER edited directly: every change requires an OPEN release (status=publishing; at most
 * one, DB-enforced), goes through this service -> Tx -> WritePath, appends a typed
 * node_events ledger row, and fails closed with a coded TaxonomyChangeException.
 * Historical truth: classification_history rows and per-release snapshots are never
 * rewritten; affected products are STAMPED with reclassification work items and keep the
 * release under which their classification was decided until T4 reclassifies them.
 */
@Service
public class TaxonomyChangeService {

    /** TR2-CURRENT-1 — the single explicit pointer in system_config. */
    public static final String CONSUMER_RELEASE_POINTER = "consumer_taxonomy_release";

    private static final Map<String, String> REQUIRED_PARENT = Map.of(
            "vertical", "sub_category",
            "sub_category", "category",
            "category", "super_category");

    private final Tx tx;
    private final WritePath writePath;
    private final ReleaseGate releaseGate;

    public TaxonomyChangeService(Tx tx, WritePath writePath, ReleaseGate releaseGate) {
        this.tx = tx;
        this.writePath = writePath;
        this.releaseGate = releaseGate;
    }

    // ---------- release state machine ----------

    /** Open a release. DB partial-unique index guarantees at most one open release. */
    public void openRelease(String releaseId, String basedOn) {
        tx.run(session -> {
            EventPayload e = ev("RELEASE_OPENED", Map.of("release", releaseId));
            try {
                writePath.auxWrite(session, "catalogue_releases", e, c -> c.insertOne(session,
                        new Document("_id", releaseId).append("status", "publishing")
                                .append("gate", "OPEN")
                                .append("based_on", basedOn).append("opened_at", new Date())));
            } catch (MongoWriteException ex) {
                if (ex.getError().getCode() == 11000) {
                    throw new TaxonomyChangeException("RELEASE_ALREADY_OPEN",
                            "another release is open or this id exists: " + releaseId);
                }
                throw ex;
            }
        });
    }

    /**
     * Snapshot every node then flip publishing->active. Resumable: snapshot upserts are
     * idempotent; a crash leaves the release in `publishing` (unusable, fail-closed) and a
     * re-run completes it. crashAfterBatches >= 0 is the K-series fault-injection hook.
     */
    public void activateRelease(String releaseId, int batchSize, int crashAfterBatches) {
        Document rel = writePath.database().getCollection("catalogue_releases")
                .find(Filters.eq("_id", releaseId)).first();
        if (rel == null || !List.of("publishing", "freezing").contains(rel.getString("status"))) {
            throw new TaxonomyChangeException("RELEASE_NOT_OPEN",
                    "release missing or not open: " + releaseId);
        }
        // M3 fix: FREEZE first. publishing->freezing CASes the same doc every change txn
        // fences ($inc change_seq), so in-flight changes either commit before the freeze or
        // abort on conflict; after the freeze, requireOpenRelease rejects (fail-closed) and
        // the tree is immutable for the whole snapshot. A crash mid-snapshot leaves
        // `freezing` — changes stay blocked, resume completes the identical snapshot.
        if ("publishing".equals(rel.getString("status"))) {
            tx.run(session -> writePath.auxWrite(session, "catalogue_releases",
                    ev("RELEASE_FREEZING", Map.of("release", releaseId)), c -> {
                        long n = c.updateOne(session,
                                Filters.and(Filters.eq("_id", releaseId),
                                        Filters.eq("status", "publishing")),
                                Updates.set("status", "freezing")).getModifiedCount();
                        if (n == 0) {
                            throw new TaxonomyChangeException("RELEASE_NOT_OPEN",
                                    "lost freeze race: " + releaseId);
                        }
                    }));
        }
        List<Document> nodes = writePath.database().getCollection("taxonomy_nodes")
                .find().into(new ArrayList<>());
        int batches = 0;
        for (int i = 0; i < nodes.size(); i += batchSize) {
            List<Document> batch = nodes.subList(i, Math.min(i + batchSize, nodes.size()));
            tx.run(session -> {
                EventPayload e = ev("RELEASE_SNAPSHOT_BATCH", Map.of("release", releaseId));
                for (Document n : batch) {
                    Document snap = new Document(n);
                    snap.remove("_id");
                    writePath.auxWrite(session, "taxonomy_snapshot_nodes", e,
                            c -> c.updateOne(session,
                                    Filters.and(Filters.eq("release_id", releaseId),
                                            Filters.eq("node_id", n.getString("_id"))),
                                    new Document("$setOnInsert",
                                            new Document(snap).append("release_id", releaseId)
                                                    .append("node_id", n.getString("_id"))),
                                    new UpdateOptions().upsert(true)));
                }
            });
            batches++;
            if (crashAfterBatches >= 0 && batches >= crashAfterBatches) {
                return; // simulated crash: release stays `publishing` — fail-closed
            }
        }
        tx.run(session -> {
            EventPayload e = ev("RELEASE_ACTIVATED", Map.of("release", releaseId));
            // Atomic attribute flip (Step 2): pending definition/schema versions authored in
            // this release become active, prior actives become superseded — in the SAME txn
            // as the status flip, so a partially active schema is unrepresentable.
            for (String coll : List.of("attribute_definitions", "attribute_schemas")) {
                String keyField = coll.equals("attribute_definitions") ? "key" : "schema_id";
                for (Document pending : writePath.database().getCollection(coll)
                        .find(session, Filters.and(Filters.eq("release_id", releaseId),
                                Filters.eq("status", "pending")))
                        // MAJOR fix: ascending version order makes the supersede+activate
                        // sequence correct even when one key has multiple pendings — the
                        // "one active version per key" invariant no longer rests on
                        // MongoDB's unspecified natural order.
                        .sort(com.mongodb.client.model.Sorts.ascending(keyField, "version"))
                        .into(new ArrayList<>())) {
                    writePath.auxWrite(session, coll, e, c -> c.updateMany(session,
                            Filters.and(Filters.eq(keyField, pending.getString(keyField)),
                                    Filters.lt("version", pending.getInteger("version")),
                                    Filters.or(Filters.eq("status", "active"),
                                            Filters.exists("status", false))),
                            Updates.set("status", "superseded")));
                    writePath.auxWrite(session, coll, e, c -> c.updateOne(session,
                            Filters.and(Filters.eq(keyField, pending.getString(keyField)),
                                    Filters.eq("version", pending.getInteger("version"))),
                            Updates.set("status", "active")));
                    if (coll.equals("attribute_schemas")
                            && Boolean.TRUE.equals(pending.getBoolean("compat_breaking"))) {
                        for (Document vertical : writePath.database().getCollection("taxonomy_nodes")
                                .find(session, Filters.and(
                                        Filters.eq("attribute_schema_id", pending.getString("schema_id")),
                                        Filters.eq("node_type", "vertical"),
                                        Filters.eq("status", "active")))
                                .into(new ArrayList<>())) {
                            stampAffectedProducts(session, e, vertical.getString("_id"),
                                    releaseId, "attribute_revalidation");
                        }
                    }
                }
            }
            writePath.auxWrite(session, "catalogue_releases", e,
                    c -> c.updateOne(session, Filters.eq("_id", releaseId),
                            Updates.combine(Updates.set("status", "active"),
                                    Updates.unset("gate"),
                                    Updates.set("activated_at", new Date()))));
            // TR2-CURRENT-1: the consumer "current release" pointer moves in THIS transaction,
            // with the status flip. Two writes would leave a window in which the pointer names a
            // release whose snapshot is incomplete — and "current" must never be inferred from a
            // timestamp, a lexical id, or whichever active row Mongo returns first.
            writePath.auxWrite(session, "system_config", e, c -> c.updateOne(session,
                    Filters.eq("_id", CONSUMER_RELEASE_POINTER),
                    Updates.combine(Updates.set("release_id", releaseId),
                            Updates.set("updated_at", new Date())),
                    new UpdateOptions().upsert(true)));
        });
    }

    public void activateRelease(String releaseId) {
        activateRelease(releaseId, 200, -1);
    }

    /** Record the frozen baseline (v0.9.0) as an active, snapshotted release. */
    public void recordBaseline(String releaseId) {
        try {
            openRelease(releaseId, null);
        } catch (TaxonomyChangeException e) {
            if (!"RELEASE_ALREADY_OPEN".equals(e.code)) throw e;
        }
        activateRelease(releaseId);
    }

    // ---------- change operations (all require an open release) ----------

    public void renameNode(String nodeId, int expectedVersion, String newName) {
        tx.run(session -> {
            EventPayload e = ev("NODE_RENAMED", Map.of("node", nodeId, "to", newName));
            String rel = releaseGate.requireOpen(session, e);
            Document node = activeNode(session, nodeId);
            String old = node.getString("name");
            // duplicate-sibling guard (review m-finding): rename may not collide with an
            // active sibling under the same parent
            long clash = writePath.database().getCollection("taxonomy_nodes")
                    .countDocuments(session, Filters.and(
                            Filters.eq("parent_id", node.getString("parent_id")),
                            Filters.eq("name", newName), Filters.eq("status", "active"),
                            new Document("_id", new Document("$ne", nodeId))));
            if (clash > 0) {
                throw new TaxonomyChangeException("DUPLICATE_NODE",
                        "active sibling already named: " + newName);
            }
            casNode(session, nodeId, expectedVersion, Updates.set("name", newName), e);
            nodeEvent(session, e, nodeId, "renamed", rel,
                    new Document("from", old).append("to", newName));
        });
    }

    public void moveNode(String nodeId, int expectedVersion, String newParentId) {
        tx.run(session -> {
            EventPayload e = ev("NODE_MOVED", Map.of("node", nodeId, "to", newParentId));
            String rel = releaseGate.requireOpen(session, e);
            Document node = activeNode(session, nodeId);
            if (nodeId.equals(newParentId)) {
                throw new TaxonomyChangeException("CYCLE", "node cannot be its own parent");
            }
            Document parent = writePath.database().getCollection("taxonomy_nodes")
                    .find(session, Filters.eq("_id", newParentId)).first();
            if (parent == null || !"active".equals(parent.getString("status"))) {
                throw new TaxonomyChangeException("INVALID_PARENT",
                        "parent missing or not active: " + newParentId);
            }
            String required = REQUIRED_PARENT.get(node.getString("node_type"));
            if (required == null || !required.equals(parent.getString("node_type"))) {
                throw new TaxonomyChangeException("INVALID_PARENT_LEVEL",
                        node.getString("node_type") + " requires parent " + required
                                + ", got " + parent.getString("node_type"));
            }
            // defense-in-depth: strict level typing makes longer cycles unrepresentable,
            // but walk the ancestor chain anyway (Law 4: never rely on one guard).
            String cursor = parent.getString("parent_id");
            while (cursor != null) {
                if (cursor.equals(nodeId)) {
                    throw new TaxonomyChangeException("CYCLE", "move would create a cycle");
                }
                Document a = writePath.database().getCollection("taxonomy_nodes")
                        .find(session, Filters.eq("_id", cursor)).first();
                cursor = a == null ? null : a.getString("parent_id");
            }
            String oldParent = node.getString("parent_id");
            casNode(session, nodeId, expectedVersion, Updates.set("parent_id", newParentId), e);
            nodeEvent(session, e, nodeId, "re_parented", rel,
                    new Document("from", oldParent).append("to", newParentId));
            stampAffectedProducts(session, e, nodeId, rel, "attribute_revalidation");
        });
    }

    /** Merge loser vertical into survivor. Blocked on schema conflict unless reconciled. */
    public void mergeNodes(String loserId, int expectedVersion, String survivorId,
                           boolean schemaReconciliationApproved) {
        tx.run(session -> {
            EventPayload e = ev("NODE_MERGED", Map.of("loser", loserId, "survivor", survivorId));
            String rel = releaseGate.requireOpen(session, e);
            Document loser = activeNode(session, loserId);
            Document survivor = activeNode(session, survivorId);
            if (loserId.equals(survivorId)) {
                throw new TaxonomyChangeException("INVALID_MERGE", "cannot merge a node into itself");
            }
            if (!"vertical".equals(loser.getString("node_type"))
                    || !"vertical".equals(survivor.getString("node_type"))) {
                throw new TaxonomyChangeException("INVALID_MERGE", "V1 merges verticals only");
            }
            String ls = loser.getString("attribute_schema_id");
            String ss = survivor.getString("attribute_schema_id");
            if (ls != null && !ls.equals(ss) && !schemaReconciliationApproved) {
                throw new TaxonomyChangeException("SCHEMA_CONFLICT",
                        "schemas differ (" + ls + " vs " + ss + ") — reconciliation map required");
            }
            casNode(session, loserId, expectedVersion, Updates.combine(
                    Updates.set("status", "merged"), Updates.set("merged_into", survivorId)), e);
            writePath.auxWrite(session, "aliases", e, c -> c.updateMany(session,
                    Filters.eq("node_id", loserId), Updates.set("node_id", survivorId)));
            nodeEvent(session, e, loserId, "merged", rel, new Document("into", survivorId));
            stampAffectedProducts(session, e, loserId, rel, "reclassification");
        });
    }

    /** Split a vertical: children minted with NEW ids, parent deprecated (never reused). */
    public List<String> splitNode(String nodeId, int expectedVersion, List<String> childNames) {
        List<String> minted = new ArrayList<>();
        tx.run(session -> {
            minted.clear();
            EventPayload e = ev("NODE_SPLIT", Map.of("node", nodeId));
            String rel = releaseGate.requireOpen(session, e);
            Document node = activeNode(session, nodeId);
            if (!"vertical".equals(node.getString("node_type"))) {
                throw new TaxonomyChangeException("INVALID_SPLIT", "V1 splits verticals only");
            }
            if (childNames == null || childNames.size() < 2
                    || childNames.stream().distinct().count() != childNames.size()) {
                throw new TaxonomyChangeException("INVALID_SPLIT",
                        "split requires >=2 DISTINCT child names");
            }
            for (String name : childNames) {
                long dup = writePath.database().getCollection("taxonomy_nodes")
                        .countDocuments(session, Filters.and(
                                Filters.eq("parent_id", node.getString("parent_id")),
                                Filters.eq("name", name), Filters.eq("status", "active")));
                if (dup > 0) {
                    throw new TaxonomyChangeException("DUPLICATE_NODE",
                            "active sibling with name already exists: " + name);
                }
                String id = nextVerticalId(session, e);
                minted.add(id);
                writePath.insertWithEvent(session, "taxonomy_nodes", new Document("_id", id)
                        .append("node_type", "vertical").append("name", name)
                        .append("parent_id", node.getString("parent_id"))
                        .append("attribute_schema_id", node.getString("attribute_schema_id"))
                        .append("status", "active").append("created_in_version", rel)
                        .append("version", 1), e);
            }
            casNode(session, nodeId, expectedVersion, Updates.set("status", "deprecated"), e);
            nodeEvent(session, e, nodeId, "split", rel, new Document("children", minted));
            stampAffectedProducts(session, e, nodeId, rel, "reclassification");
        });
        return minted;
    }

    public void deprecateNode(String nodeId, int expectedVersion) {
        tx.run(session -> {
            EventPayload e = ev("NODE_DEPRECATED", Map.of("node", nodeId));
            String rel = releaseGate.requireOpen(session, e);
            Document node = activeNode(session, nodeId);
            long activeChildren = writePath.database().getCollection("taxonomy_nodes")
                    .countDocuments(session, Filters.and(Filters.eq("parent_id", nodeId),
                            Filters.eq("status", "active")));
            if (activeChildren > 0) {
                throw new TaxonomyChangeException("HAS_ACTIVE_CHILDREN",
                        "deprecate children first: " + activeChildren + " active");
            }
            casNode(session, nodeId, expectedVersion, Updates.set("status", "deprecated"), e);
            nodeEvent(session, e, nodeId, "deprecated", rel, new Document());
        });
    }

    /** Revive a deprecated node (approved model: deprecate/revive). Merged nodes are
     *  terminal — reviving one would resurrect an identity that products redirect away
     *  from, so it is refused. Parent must be active or the tree would be inconsistent. */
    public void reviveNode(String nodeId, int expectedVersion) {
        tx.run(session -> {
            EventPayload e = ev("NODE_REVIVED", Map.of("node", nodeId));
            String rel = releaseGate.requireOpen(session, e);
            Document node = writePath.database().getCollection("taxonomy_nodes")
                    .find(session, Filters.eq("_id", nodeId)).first();
            if (node == null) throw new TaxonomyChangeException("NODE_NOT_FOUND", nodeId);
            if (!"deprecated".equals(node.getString("status"))) {
                throw new TaxonomyChangeException("NOT_DEPRECATED",
                        nodeId + " is " + node.getString("status") + "; only deprecated nodes revive");
            }
            String parentId = node.getString("parent_id");
            if (parentId != null) {
                Document parent = writePath.database().getCollection("taxonomy_nodes")
                        .find(session, Filters.eq("_id", parentId)).first();
                if (parent == null || !"active".equals(parent.getString("status"))) {
                    throw new TaxonomyChangeException("INVALID_PARENT",
                            "parent is not active: " + parentId);
                }
            }
            long clash = writePath.database().getCollection("taxonomy_nodes")
                    .countDocuments(session, Filters.and(Filters.eq("parent_id", parentId),
                            Filters.eq("name", node.getString("name")),
                            Filters.eq("status", "active")));
            if (clash > 0) {
                throw new TaxonomyChangeException("DUPLICATE_NODE",
                        "an active sibling now holds that name: " + node.getString("name"));
            }
            casNode(session, nodeId, expectedVersion, Updates.set("status", "active"), e);
            nodeEvent(session, e, nodeId, "revived", rel, new Document());
        });
    }

    // ---------- internals ----------

    private Document activeNode(ClientSession session, String nodeId) {
        Document node = writePath.database().getCollection("taxonomy_nodes")
                .find(session, Filters.eq("_id", nodeId)).first();
        if (node == null) {
            throw new TaxonomyChangeException("NODE_NOT_FOUND", nodeId);
        }
        if (!"active".equals(node.getString("status"))) {
            throw new TaxonomyChangeException("NODE_NOT_ACTIVE",
                    nodeId + " is " + node.getString("status"));
        }
        return node;
    }

    private void casNode(ClientSession session, String nodeId, int expectedVersion,
                         Bson update, EventPayload e) {
        writePath.auxWrite(session, "taxonomy_nodes", e, c -> {
            long n = c.updateOne(session,
                    Filters.and(Filters.eq("_id", nodeId), Filters.eq("version", expectedVersion)),
                    Updates.combine(update, Updates.inc("version", 1))).getModifiedCount();
            if (n == 0) {
                throw new CasConflictException("taxonomy_nodes/" + nodeId
                        + " expectedVersion=" + expectedVersion);
            }
        });
    }

    private void nodeEvent(ClientSession session, EventPayload e, String nodeId,
                           String eventType, String releaseId, Document detail) {
        writePath.auxWrite(session, "node_events", e, c -> c.insertOne(session,
                new Document("node_id", nodeId).append("event", eventType)
                        .append("release_id", releaseId).append("detail", detail)
                        .append("at", new Date())));
    }

    /** M4 fix: the change txn enqueues ONE deterministic scan item (bounded txn size);
     *  runStampWorker() fans it out to per-product items in idempotent chunks. Affected
     *  products keep their decided-at release; nothing on the product is rewritten. */
    private void stampAffectedProducts(ClientSession session, EventPayload e, String verticalId,
                                       String releaseId, String workType) {
        writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                Filters.eq("_id", "stamp_scan:" + workType + ":" + verticalId + ":" + releaseId),
                new Document("$setOnInsert", new Document("type", "stamp_scan")
                        .append("work_type", workType).append("node_id", verticalId)
                        .append("release_id", releaseId).append("status", "pending")
                        .append("checkpoint", null).append("created_at", new Date())),
                new UpdateOptions().upsert(true)));
    }

    /** Chunked, lease-claimed, checkpointed fan-out (same pattern as the taint worker). */
    public void runStampWorker(int batchSize) {
        while (true) {
            Document item = writePath.database().getCollection("work_queue").findOneAndUpdate(
                    Filters.and(Filters.eq("type", "stamp_scan"),
                            Filters.or(Filters.eq("status", "pending"),
                                    Filters.and(Filters.eq("status", "leased"),
                                            Filters.lt("lease_until", new Date())))),
                    Updates.combine(Updates.set("status", "leased"),
                            Updates.set("lease_owner", Thread.currentThread().getName()),
                            Updates.set("lease_until", new Date(System.currentTimeMillis() + 60_000))));
            if (item == null) return;
            String verticalId = item.getString("node_id");
            String releaseId = item.getString("release_id");
            String workType = item.getString("work_type");
            String checkpoint = item.getString("checkpoint");
            while (true) {
                Bson filter = checkpoint == null
                        ? Filters.eq("classification.vertical_id", verticalId)
                        : Filters.and(Filters.eq("classification.vertical_id", verticalId),
                                Filters.gt("_id", checkpoint));
                List<Document> page = writePath.database().getCollection("products")
                        .find(filter).projection(new Document("_id", 1))
                        .sort(new Document("_id", 1)).limit(batchSize)
                        .into(new ArrayList<>());
                if (page.isEmpty()) break;
                String last = page.get(page.size() - 1).getString("_id");
                tx.run(session -> {
                    EventPayload e = ev("STAMP_BATCH", Map.of("node", verticalId, "n", page.size()));
                    for (Document p : page) {
                        String pid = p.getString("_id");
                        writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                                Filters.eq("_id", workType + ":" + pid + ":" + releaseId),
                                new Document("$setOnInsert", new Document("type", workType)
                                        .append("product_id", pid).append("node_id", verticalId)
                                        .append("release_id", releaseId).append("status", "pending")
                                        .append("created_at", new Date())),
                                new UpdateOptions().upsert(true)));
                    }
                    writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                            Filters.eq("_id", item.getString("_id")),
                            Updates.set("checkpoint", last)));
                });
                checkpoint = last;
            }
            tx.run(session -> writePath.auxWrite(session, "work_queue",
                    ev("STAMP_SCAN_DONE", Map.of("node", verticalId)),
                    c -> c.updateOne(session, Filters.eq("_id", item.getString("_id")),
                            Updates.set("status", "completed"))));
        }
    }

    private String nextVerticalId(ClientSession session, EventPayload e) {
        // Initialize the counter ONCE at 100000 (far past seed max TZV-000293), then every
        // caller — including two mints inside one split — gets a strictly increasing value.
        if (writePath.database().getCollection("id_sequences")
                .find(session, Filters.eq("_id", "TZV")).first() == null) {
            try {
                writePath.auxWrite(session, "id_sequences", e, c -> c.insertOne(session,
                        new Document("_id", "TZV").append("seq", 100000L)));
            } catch (MongoWriteException ex) {
                if (ex.getError().getCode() != 11000) throw ex; // concurrent init = fine
            }
        }
        Document[] seq = new Document[1];
        writePath.auxWrite(session, "id_sequences", e, c -> seq[0] = c.findOneAndUpdate(session,
                Filters.eq("_id", "TZV"), Updates.inc("seq", 1),
                new com.mongodb.client.model.FindOneAndUpdateOptions()
                        .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)));
        return String.format("TZV-%06d", seq[0].get("seq", Number.class).longValue());
    }

    private EventPayload ev(String type, Map<String, Object> detail) {
        return new EventPayload(type, "TZP-SYSTEM", detail);
    }
}
