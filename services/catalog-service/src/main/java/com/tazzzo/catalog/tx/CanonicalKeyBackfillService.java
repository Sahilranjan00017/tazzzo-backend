package com.tazzzo.catalog.tx;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.domain.PackOf;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.CanonicalKey;
import com.tazzzo.catalog.schema.CanonicalKeyService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WP-6 — canonical-key backfill for products that were minted before CAT-ID existed.
 *
 * Deliberately the SAME shape as TaxonomyChangeService.runStampWorker rather than a new
 * migration harness: lease-claimed work items, _id-ordered pagination, and a checkpoint written
 * in the same transaction as the batch. A crash resumes; it does not restart.
 *
 * Every write goes through WritePath.setCanonicalKeyOnce, which is write-once — so re-running
 * this worker, running it concurrently, or running it against a partially migrated corpus can
 * only ever be a no-op. It cannot overwrite an identity.
 *
 * Three outcomes per product, and NONE of them mutates catalogue truth beyond the key:
 *   keyable + free   -> key set, canonical_keys row inserted
 *   not keyable      -> identity_incomplete work item (Gate 2)
 *   key already TAKEN-> backfill_collision work item. NEVER merged, never rebound, no winner
 *                       picked. A collision is a FINDING: either a genuine pre-existing duplicate
 *                       or a discriminating list too coarse for that vertical.
 */
@Service
public class CanonicalKeyBackfillService {

    private final Tx tx;
    private final WritePath writePath;
    private final CanonicalKeyService canonicalKeys;

    public CanonicalKeyBackfillService(Tx tx, WritePath writePath, CanonicalKeyService canonicalKeys) {
        this.tx = tx;
        this.writePath = writePath;
        this.canonicalKeys = canonicalKeys;
    }

    /** Queues one vertical for backfill. Idempotent: re-seeding an in-flight scan is a no-op. */
    public void seed(String verticalId) {
        tx.run(session -> writePath.auxWrite(session, "work_queue",
                new EventPayload("CK_BACKFILL_SEEDED", verticalId, Map.of("vertical", verticalId)),
                c -> c.updateOne(session, Filters.eq("_id", "ck_backfill:" + verticalId),
                        new Document("$setOnInsert", new Document("type", "ck_backfill")
                                .append("vertical_id", verticalId).append("status", "pending")
                                .append("created_at", new Date())),
                        new UpdateOptions().upsert(true))));
    }

    public void runBackfillWorker(int batchSize) {
        while (true) {
            Document item = writePath.database().getCollection("work_queue").findOneAndUpdate(
                    Filters.and(Filters.eq("type", "ck_backfill"),
                            Filters.or(Filters.eq("status", "pending"),
                                    Filters.and(Filters.eq("status", "leased"),
                                            Filters.lt("lease_until", new Date())))),
                    Updates.combine(Updates.set("status", "leased"),
                            Updates.set("lease_owner", Thread.currentThread().getName()),
                            Updates.set("lease_until", new Date(System.currentTimeMillis() + 60_000))));
            if (item == null) return;
            String verticalId = item.getString("vertical_id");
            String checkpoint = item.getString("checkpoint");
            while (true) {
                Bson filter = checkpoint == null
                        ? Filters.eq("classification.vertical_id", verticalId)
                        : Filters.and(Filters.eq("classification.vertical_id", verticalId),
                                Filters.gt("_id", checkpoint));
                List<Document> page = writePath.database().getCollection("products")
                        .find(filter).sort(new Document("_id", 1)).limit(batchSize)
                        .into(new ArrayList<>());
                if (page.isEmpty()) break;
                String last = page.get(page.size() - 1).getString("_id");
                for (Document p : page) {
                    backfillOne(p);
                }
                final String cp = last;
                tx.run(session -> writePath.auxWrite(session, "work_queue",
                        new EventPayload("CK_BACKFILL_BATCH", verticalId,
                                Map.of("vertical", verticalId, "n", page.size())),
                        c -> c.updateOne(session, Filters.eq("_id", item.getString("_id")),
                                Updates.set("checkpoint", cp))));
                checkpoint = last;
            }
            tx.run(session -> writePath.auxWrite(session, "work_queue",
                    new EventPayload("CK_BACKFILL_DONE", verticalId, Map.of("vertical", verticalId)),
                    c -> c.updateOne(session, Filters.eq("_id", item.getString("_id")),
                            Updates.set("status", "completed"))));
        }
    }

    private void backfillOne(Document p) {
        String productId = p.getString("_id");
        Document identity = p.get("identity", Document.class);
        if (identity != null && identity.getString("canonical_key") != null) return; // already keyed
        Document classification = p.get("classification", Document.class);
        String verticalId = classification == null ? null : classification.getString("vertical_id");
        Document attrs = p.get("attributes", Document.class);
        Document packOfDoc = p.get("pack_of", Document.class);
        PackOf packOf = packOfDoc == null ? null
                : new PackOf(packOfDoc.getString("component_product_id"), packOfDoc.getInteger("qty"));

        Optional<CanonicalKey> key = canonicalKeys.derive(verticalId, p.getString("brand_code"),
                p.getString("product_type"), attrs == null ? Map.of() : attrs, packOf);

        if (key.isEmpty()) {
            queue(productId, "identity_incomplete:" + productId,
                    new Document("type", "identity_incomplete").append("product_id", productId)
                            .append("vertical_id", verticalId));
            return;
        }
        try {
            tx.run(session -> {
                EventPayload e = new EventPayload("CK_BACKFILLED", productId,
                        Map.of("key", key.get().key()));
                writePath.auxWrite(session, "canonical_keys", e, c -> c.insertOne(session,
                        new Document("_id", key.get().key()).append("product_id", productId)
                                .append("version", key.get().version())
                                .append("status", "active").append("created_at", new Date())));
                // E-3: the backfill stamps the SAME per-vertical ratification version.
                writePath.setCanonicalKeyOnce(session, productId, key.get().key(),
                        key.get().version(), e);
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // FINDING, not an error: two existing products derive one key.
                queue(productId, "ck_backfill_collision:" + key.get().key(),
                        new Document("type", "ck_backfill_collision").append("product_id", productId)
                                .append("canonical_key", key.get().key()).append("vertical_id", verticalId));
                return;
            }
            throw e;
        }
    }

    private void queue(String productId, String id, Document body) {
        tx.run(session -> writePath.auxWrite(session, "work_queue",
                new EventPayload("CK_BACKFILL_ITEM", productId, Map.of("item", id)),
                c -> c.replaceOne(session, Filters.eq("_id", id),
                        body.append("_id", id).append("status", "pending")
                                .append("created_at", new Date()),
                        new ReplaceOptions().upsert(true))));
    }
}
