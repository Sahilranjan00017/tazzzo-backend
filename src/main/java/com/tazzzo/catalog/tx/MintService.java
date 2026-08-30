package com.tazzzo.catalog.tx;

import com.mongodb.MongoWriteException;
import com.tazzzo.catalog.domain.GtinBinding;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** T1 — mint: identity gate + product + initial classification + event, one transaction. */
@Service
public class MintService {

    public static final String UNCLASSIFIED = "TZV-UNCLASSIFIED";
    public static final String SCOPE_BLOCKED = "TZV-SCOPE-BLOCKED";

    private final Tx tx;
    private final WritePath writePath;
    private final AttributeGovernanceService governance;

    public MintService(Tx tx, WritePath writePath, AttributeGovernanceService governance) {
        this.tx = tx;
        this.writePath = writePath;
        this.governance = governance;
    }

    public String mint(ProductDraft d) {
        // Service-tier enforcement (the I-6 bypass closes HERE, not in the validator):
        List<org.bson.Document> governanceItems = governance.validate(
                d.verticalId(), d.attributes() == null ? Map.of() : d.attributes(), d.evidenceRefs());
        tx.run(session -> {
            EventPayload minted = new EventPayload("MINTED", d.id(), Map.of("brand", d.brandCode()));
            try {
                if ("internal".equals(d.identityType())) {
                    writePath.auxWrite(session, "identity_keys", minted, c -> c.insertOne(session,
                            new Document("_id", d.internalKey())
                                    .append("product_id", d.id()).append("status", "active")));
                } else {
                    List<GtinBinding> gtins = d.gtins() == null ? List.of() : d.gtins();
                    for (GtinBinding g : gtins) {
                        writePath.auxWrite(session, "gtin_registry", minted, c -> c.insertOne(session,
                                new Document("_id", g.value()).append("bindings", List.of(
                                        new Document("product_id", d.id()).append("market", g.market())
                                                .append("from", new Date()).append("to", null)))));
                    }
                }
            } catch (MongoWriteException e) {
                if (e.getError().getCode() == 11000) {
                    throw new IdentityCollisionException("identity already minted: " + d.id());
                }
                throw e;
            }
            writePath.insertWithEvent(session, "products", ProductDocuments.fromDraft(d), minted);
            writePath.auxWrite(session, "classification_history", minted, c -> c.insertOne(session,
                    new Document("product_id", d.id())
                            .append("vertical_id", d.verticalId())
                            .append("release_id", d.releaseId())
                            .append("status", d.classificationStatus())
                            .append("method", "mint")
                            .append("decided_at", new Date())));
            for (org.bson.Document item : governanceItems) {
                writePath.auxWrite(session, "work_queue", minted, c -> c.replaceOne(session,
                        new Document("_id", item.getString("_id")), item.append("created_at", new Date()),
                        new com.mongodb.client.model.ReplaceOptions().upsert(true)));
            }
            if (UNCLASSIFIED.equals(d.verticalId()) || SCOPE_BLOCKED.equals(d.verticalId())) {
                writePath.auxWrite(session, "work_queue", minted, c -> c.insertOne(session,
                        new Document("type", "classification_review").append("product_id", d.id())
                                .append("status", "pending").append("created_at", new Date())));
            }
        });
        return d.id();
    }
}
