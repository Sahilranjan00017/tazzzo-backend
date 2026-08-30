package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** T4 — classification: history insert + projection CAS + evidence links + event, one txn. */
@Service
public class ClassifyService {

    private static final Set<String> STATUSES = Set.of("confirmed", "provisional", "review", "scope_blocked");

    private final Tx tx;
    private final WritePath writePath;

    public ClassifyService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void classify(String productId, String verticalId, String releaseId,
                         String status, double confidence, List<String> evidenceIds) {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("invalid classification status: " + status);
        }
        tx.run(session -> {
            EventPayload classified = new EventPayload("CLASSIFIED", productId,
                    Map.of("vertical", verticalId, "release", releaseId, "status", status));
            writePath.auxWrite(session, "classification_history", classified, c -> c.insertOne(session,
                    new Document("product_id", productId).append("vertical_id", verticalId)
                            .append("release_id", releaseId).append("status", status)
                            .append("confidence", confidence)
                            .append("method", "rule").append("decided_at", new Date())));
            Document product = writePath.database().getCollection("products")
                    .find(session, Filters.eq("_id", productId)).first();
            if (product == null) throw new ProductNotFoundException(productId);
            writePath.casUpdateWithEvent(session, "products", productId, product.getInteger("version"),
                    Updates.combine(
                            Updates.set("classification.vertical_id", verticalId),
                            Updates.set("classification.release_id", releaseId),
                            Updates.set("classification.status", status),
                            Updates.set("classification.confidence", confidence),
                            Updates.inc("version", 1)), classified);
            for (String ev : evidenceIds) {
                writePath.auxWrite(session, "evidence_links", classified, c -> c.updateOne(session,
                        Filters.and(Filters.eq("evidence_id", ev), Filters.eq("product_id", productId),
                                Filters.eq("link_type", "classification")),
                        Updates.combine(Updates.set("link_type", "classification"),
                                Updates.set("active", true)),
                        new UpdateOptions().upsert(true)));
            }
            if (MintService.UNCLASSIFIED.equals(verticalId) || MintService.SCOPE_BLOCKED.equals(verticalId)) {
                writePath.auxWrite(session, "work_queue", classified, c -> c.insertOne(session,
                        new Document("type", "classification_review").append("product_id", productId)
                                .append("status", "pending").append("created_at", new Date())));
            }
        });
    }
}
