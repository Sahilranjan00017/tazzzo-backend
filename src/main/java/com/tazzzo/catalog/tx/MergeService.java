package com.tazzzo.catalog.tx;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * T2 — merge. startMerge: one txn (both products -> merging, identity redirect, MERGE_STARTED
 * event, durable outbox item INSIDE the txn). runFinalizer: idempotent, resumable — this is
 * the K-1 mechanism: safe to call after a crash, safe to call twice.
 */
@Service
public class MergeService {

    private final Tx tx;
    private final WritePath writePath;

    public MergeService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void startMerge(String loserId, String survivorId) {
        tx.run(session -> {
            Document loser = mustGet(session, loserId);
            Document survivor = mustGet(session, survivorId);
            if (!"active".equals(loser.getString("lifecycle")) || !"active".equals(survivor.getString("lifecycle"))) {
                throw new IllegalStateException("both products must be active to merge");
            }
            EventPayload started = new EventPayload("MERGE_STARTED", loserId,
                    Map.of("survivor", survivorId, "manifest", List.of("offers", "bundles")));
            writePath.casUpdateWithEvent(session, "products", loserId, loser.getInteger("version"),
                    Updates.combine(Updates.set("lifecycle", "merging"), Updates.inc("version", 1)), started);
            writePath.casUpdateWithEvent(session, "products", survivorId, survivor.getInteger("version"),
                    Updates.combine(Updates.set("lifecycle", "merging"), Updates.inc("version", 1)), started);
            Document identity = loser.get("identity", Document.class);
            if ("internal".equals(identity.getString("type")) && identity.getString("internal_key") != null) {
                writePath.auxWrite(session, "identity_keys", started, c -> c.updateOne(session,
                        Filters.eq("_id", identity.getString("internal_key")),
                        Updates.combine(Updates.set("status", "redirected"),
                                Updates.set("redirected_to", survivorId))));
            }
            // Transactional outbox: the repoint obligation cannot be lost by a crash (M3).
            writePath.auxWrite(session, "work_queue", started, c -> c.insertOne(session,
                    new Document("_id", "merge:" + loserId + ":" + survivorId)
                            .append("type", "merge_repoint")
                            .append("loser", loserId).append("survivor", survivorId)
                            .append("manifest", List.of("offers", "bundles"))
                            .append("completed", List.of())
                            .append("status", "pending")
                            .append("created_at", new Date())));
        });
    }

    /** Idempotent + resumable. Running it twice, or after a crash, is always safe. */
    public void runFinalizer() {
        while (true) {
            // Claim via CAS lease (C-6): concurrent finalizers can never process the same item.
            Document item = writePath.database().getCollection("work_queue").findOneAndUpdate(
                    Filters.and(Filters.eq("type", "merge_repoint"),
                            Filters.or(Filters.eq("status", "pending"),
                                    Filters.and(Filters.eq("status", "leased"),
                                            Filters.lt("lease_until", new Date())))),
                    Updates.combine(Updates.set("status", "leased"),
                            Updates.set("lease_owner", Thread.currentThread().getName()),
                            Updates.set("lease_until", new Date(System.currentTimeMillis() + 60_000))));
            if (item == null) return;
            String loser = item.getString("loser");
            String survivor = item.getString("survivor");
            tx.run(session -> {
                EventPayload done = new EventPayload("MERGE_COMPLETED", loser, Map.of("survivor", survivor));
                repointOffers(session, loser, survivor, done);
                repointBundles(session, loser, survivor, done);
                Document l = mustGet(session, loser);
                if ("merging".equals(l.getString("lifecycle"))) {
                    writePath.casUpdateWithEvent(session, "products", loser, l.getInteger("version"),
                            Updates.combine(Updates.set("lifecycle", "merged"),
                                    Updates.set("merged_into", survivor), Updates.inc("version", 1)), done);
                }
                Document s = mustGet(session, survivor);
                if ("merging".equals(s.getString("lifecycle"))) {
                    writePath.casUpdateWithEvent(session, "products", survivor, s.getInteger("version"),
                            Updates.combine(Updates.set("lifecycle", "active"), Updates.inc("version", 1)), done);
                }
                writePath.auxWrite(session, "work_queue", done, c -> c.updateOne(session,
                        Filters.eq("_id", item.getString("_id")),
                        Updates.combine(Updates.set("status", "completed"),
                                Updates.set("completed", List.of("offers", "bundles")))));
            });
        }
    }

    private void repointOffers(ClientSession session, String loser, String survivor, EventPayload e) {
        MongoCollection<Document> offers = writePath.database().getCollection("offers_current");
        for (Document offer : offers.find(session, Filters.eq("product_id", loser)).into(new ArrayList<>())) {
            boolean survivorHas = offers.countDocuments(session, Filters.and(
                    Filters.eq("product_id", survivor),
                    Filters.eq("source", offer.getString("source")),
                    Filters.eq("seller", offer.getString("seller")),
                    Filters.eq("channel", offer.getString("channel")))) > 0;
            if (survivorHas) {
                // Merge-resolution policy M4: survivor's row wins; loser's row closes.
                writePath.auxWrite(session, "offers_current", e,
                        c -> c.deleteOne(session, Filters.eq("_id", offer.get("_id"))));
            } else {
                writePath.auxWrite(session, "offers_current", e, c -> c.updateOne(session,
                        Filters.eq("_id", offer.get("_id")), Updates.set("product_id", survivor)));
            }
        }
    }

    private void repointBundles(ClientSession session, String loser, String survivor, EventPayload e) {
        MongoCollection<Document> products = writePath.database().getCollection("products");
        for (Document bundle : products.find(session,
                Filters.eq("bundle_contents.component_product_id", loser)).into(new ArrayList<>())) {
            List<Document> contents = bundle.getList("bundle_contents", Document.class);
            List<Document> rewritten = new ArrayList<>();
            for (Document comp : contents) {
                String ref = comp.getString("component_product_id").equals(loser) ? survivor
                        : comp.getString("component_product_id");
                Document existing = rewritten.stream()
                        .filter(x -> x.getString("component_product_id").equals(ref)).findFirst().orElse(null);
                if (existing != null) {
                    existing.put("qty", existing.getInteger("qty") + comp.getInteger("qty")); // M4 qty-merge dedupe
                } else {
                    Document copy = new Document(comp);
                    copy.put("component_product_id", ref);
                    rewritten.add(copy);
                }
            }
            if (rewritten.size() < 2) {
                // Law 4 fail-closed: qty-merge collapsed the bundle below the minItems:2 shape
                // invariant. The transition has no disposition rule -> block into a work item,
                // never write an invalid or silently-shrunken bundle.
                writePath.auxWrite(session, "work_queue", e, c -> c.updateOne(session,
                        Filters.eq("_id", "bundle_integrity:" + bundle.getString("_id")),
                        Updates.combine(
                                Updates.setOnInsert("type", "bundle_integrity"),
                                Updates.setOnInsert("bundle_id", bundle.getString("_id")),
                                Updates.setOnInsert("reason", "merge collapsed bundle below 2 components"),
                                Updates.setOnInsert("status", "pending"),
                                Updates.setOnInsert("created_at", new Date())),
                        new com.mongodb.client.model.UpdateOptions().upsert(true)));
            } else {
                writePath.casUpdateWithEvent(session, "products", bundle.getString("_id"),
                        bundle.getInteger("version"),
                        Updates.combine(Updates.set("bundle_contents", rewritten), Updates.inc("version", 1)), e);
            }
        }
    }

    private Document mustGet(ClientSession session, String id) {
        Document d = writePath.database().getCollection("products")
                .find(session, Filters.eq("_id", id)).first();
        if (d == null) throw new ProductNotFoundException(id);   // F2: 404, not a state conflict
        return d;
    }
}
