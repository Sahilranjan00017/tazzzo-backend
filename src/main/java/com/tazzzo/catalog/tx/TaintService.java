package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A2''': any evidence validity flip out of `active` taints every citing product.
 * The cascade is a CHECKPOINTED work item (C-5): a worker death mid-scan loses nothing —
 * the next run resumes from the recorded cursor. Per-product revalidation obligations are
 * emitted as deterministic-id work items (queue-first; no product-doc mutation, so no
 * validator change is smuggled in). K-3 proves crash-resume.
 */
@Service
public class TaintService {

    private final Tx tx;
    private final WritePath writePath;

    public TaintService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    /** Flip validity out of active AND enqueue the cascade item, one transaction. */
    public void retractEvidence(String evidenceId, String requested) {
        String newValidity = requested;
        if (newValidity == null) newValidity = "retracted";   // domain default, not transport
        if (!Set.of("retracted", "superseded").contains(newValidity)) {
            // Contract: a retracted claim is re-evidenced with a NEW record, never resurrected.
            throw new EvidenceContractException("INVALID_VALIDITY_TRANSITION",
                    "cannot flip validity to " + newValidity);
        }
        final String finalValidity = newValidity;
        tx.run(session -> {
            EventPayload e = new EventPayload("EVIDENCE_VALIDITY_FLIPPED", "TZP-SYSTEM",
                    Map.of("evidence", evidenceId, "to", finalValidity));
            Document current = writePath.database().getCollection("evidence")
                    .find(session, Filters.eq("_id", evidenceId)).first();
            if (current == null) {
                throw new EvidenceNotFoundException(evidenceId);
            }
            if (finalValidity.equals(current.getString("validity"))) {
                return;   // already in that state: no re-flip, no spurious fence bump
            }
            writePath.auxWrite(session, "evidence", e, c -> c.updateOne(session,
                    Filters.eq("_id", evidenceId),
                    Updates.combine(Updates.set("validity", finalValidity), Updates.inc("fence", 1))));
            writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                    Filters.eq("_id", "taint:" + evidenceId),
                    Updates.combine(Updates.setOnInsert("type", "taint_cascade"),
                            Updates.setOnInsert("evidence_id", evidenceId),
                            Updates.setOnInsert("status", "pending"),
                            Updates.setOnInsert("checkpoint", null),
                            Updates.setOnInsert("created_at", new Date())),
                    new UpdateOptions().upsert(true)));
        });
    }

    /**
     * Process pending taint cascades in batches; crashAfterBatches < 0 disables the fault
     * injection (test hook for K-3 — simulates the worker dying mid-scan).
     */
    /** Production entry point: no fault-injection sentinel in caller code. */
    public void runTaintWorker(int batchSize) {
        runTaintWorker(batchSize, -1);
    }

    public void runTaintWorker(int batchSize, int crashAfterBatches) {
        while (true) {
            // C-6 lease claim, mirroring MergeService: concurrent workers never share an item.
            Document item = writePath.database().getCollection("work_queue").findOneAndUpdate(
                    Filters.and(Filters.eq("type", "taint_cascade"),
                            Filters.or(Filters.eq("status", "pending"),
                                    Filters.and(Filters.eq("status", "leased"),
                                            Filters.lt("lease_until", new Date())))),
                    Updates.combine(Updates.set("status", "leased"),
                            Updates.set("lease_owner", Thread.currentThread().getName()),
                            Updates.set("lease_until", new Date(System.currentTimeMillis() + 60_000))));
            if (item == null) return;
            int batches = 0;
            while (true) {
                ObjectId checkpoint = item.getObjectId("checkpoint");
                List<Document> links = writePath.database().getCollection("evidence_links")
                        .find(Filters.and(
                                Filters.eq("evidence_id", item.getString("evidence_id")),
                                Filters.eq("active", true),
                                checkpoint == null ? Filters.empty() : Filters.gt("_id", checkpoint)))
                        .sort(Sorts.ascending("_id")).limit(batchSize).into(new ArrayList<>());
                if (links.isEmpty()) {
                    finishItem(item);
                    break;
                }
                ObjectId last = processBatch(item, links);
                item.put("checkpoint", last);
                batches++;
                if (crashAfterBatches >= 0 && batches >= crashAfterBatches) {
                    return; // simulated worker death — checkpoint is durable, resume later
                }
            }
        }
    }

    private ObjectId processBatch(Document item, List<Document> links) {
        ObjectId[] last = new ObjectId[1];
        tx.run(session -> {
            EventPayload e = new EventPayload("TAINT_BATCH", "TZP-SYSTEM",
                    Map.of("evidence", item.getString("evidence_id"), "n", links.size()));
            for (Document link : links) {
                String productId = link.getString("product_id");
                writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                        Filters.eq("_id", "attr_reval:" + item.getString("evidence_id") + ":" + productId),
                        Updates.combine(Updates.setOnInsert("type", "attribute_revalidation"),
                                Updates.setOnInsert("product_id", productId),
                                Updates.setOnInsert("evidence_id", item.getString("evidence_id")),
                                Updates.setOnInsert("status", "pending"),
                                Updates.setOnInsert("created_at", new Date())),
                        new UpdateOptions().upsert(true)));
                last[0] = link.getObjectId("_id");
            }
            // durable checkpoint, same txn as the batch it covers
            writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                    Filters.eq("_id", item.getString("_id")),
                    Updates.set("checkpoint", last[0])));
        });
        return last[0];
    }

    private void finishItem(Document item) {
        tx.run(session -> writePath.auxWrite(session, "work_queue",
                new EventPayload("TAINT_DONE", "TZP-SYSTEM",
                        Map.of("evidence", item.getString("evidence_id"))),
                c -> c.updateOne(session, Filters.eq("_id", item.getString("_id")),
                        Updates.set("status", "completed"))));
    }
}
