package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * T3 — claim publish. The $inc fence on every referenced evidence doc materializes a write
 * conflict with any concurrent validity flip (M1): two disjoint-read transactions cannot both
 * commit because their write sets now intersect on the evidence documents.
 */
@Service
public class PublishService {

    private final Tx tx;
    private final WritePath writePath;

    public PublishService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void publishClaim(String productId, String attrKey, List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            throw new EvidenceGateException("claim-tier publish requires at least one evidence reference");
        }
        tx.run(session -> {
            EventPayload published = new EventPayload("CLAIM_PUBLISHED", productId,
                    Map.of("attr", attrKey, "evidence", evidenceIds));
            for (String ev : evidenceIds) {
                writePath.auxWrite(session, "evidence", published, c -> c.updateOne(session,
                        Filters.eq("_id", ev), Updates.inc("fence", 1)));      // fence BEFORE read
            }
            for (String ev : evidenceIds) {
                Document doc = writePath.database().getCollection("evidence")
                        .find(session, Filters.eq("_id", ev)).first();
                if (doc == null || !"active".equals(doc.getString("validity"))
                        || !"readable".equals(doc.getString("payload_state"))) {
                    throw new EvidenceGateException("evidence not active+readable: " + ev);
                }
            }
            Document product = writePath.database().getCollection("products")
                    .find(session, Filters.eq("_id", productId)).first();
            if (product == null) throw new ProductNotFoundException(productId);
            writePath.casUpdateWithEvent(session, "products", productId, product.getInteger("version"),
                    Updates.combine(Updates.set("attributes." + attrKey + "_published", true),
                            Updates.inc("version", 1)), published);
            // FINDING E1 FIX: register the evidence->product edge so the taint cascade can
            // reach products whose PUBLISHED CLAIMS cite this evidence. Without this the
            // cascade only saw classification-linked products and published claims could
            // survive a retraction unnoticed. Same transaction, same WritePath pattern as
            // ClassifyService.
            for (String ev : evidenceIds) {
                writePath.auxWrite(session, "evidence_links", published, c -> c.updateOne(session,
                        Filters.and(Filters.eq("evidence_id", ev),
                                Filters.eq("product_id", productId),
                                Filters.eq("link_type", "claim")),
                        Updates.combine(Updates.set("link_type", "claim"),
                                Updates.set("attribute_key", attrKey),
                                Updates.set("active", true)),
                        new com.mongodb.client.model.UpdateOptions().upsert(true)));
            }
        });
    }

}
