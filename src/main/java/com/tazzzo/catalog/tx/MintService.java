package com.tazzzo.catalog.tx;

import com.mongodb.MongoWriteException;
import com.tazzzo.catalog.domain.GtinBinding;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.schema.CanonicalKeyService;

import java.util.Optional;
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
    private final CanonicalKeyService canonicalKeys;

    public MintService(Tx tx, WritePath writePath, AttributeGovernanceService governance,
                       CanonicalKeyService canonicalKeys) {
        this.tx = tx;
        this.writePath = writePath;
        this.governance = governance;
        this.canonicalKeys = canonicalKeys;
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
            // CAT-ID: identity is derived by Catalogue AFTER governance, so only governed,
            // schema-valid attributes can enter a key. Absence is never a failure (Gate 2).
            Optional<String> canonicalKey = canonicalKeys.derive(
                    d.verticalId(), d.brandCode(), d.productType(),
                    d.attributes() == null ? Map.of() : d.attributes(), d.packOf());
            Document productDoc = ProductDocuments.fromDraft(d);
            if (canonicalKey.isPresent()) {
                ProductDocuments.applyCanonicalKey(productDoc, canonicalKey.get(),
                        CanonicalKeyService.KEY_VERSION);
                try {
                    writePath.auxWrite(session, "canonical_keys", minted, c -> c.insertOne(session,
                            new Document("_id", canonicalKey.get()).append("product_id", d.id())
                                    .append("version", CanonicalKeyService.KEY_VERSION)
                                    .append("status", "active").append("created_at", new Date())));
                } catch (MongoWriteException e) {
                    if (e.getError().getCode() == 11000) {
                        throw new IdentityCollisionException(
                                "canonical identity already minted: " + canonicalKey.get());
                    }
                    throw e;
                }
            } else {
                writePath.auxWrite(session, "work_queue", minted, c -> c.replaceOne(session,
                        new Document("_id", "identity_incomplete:" + d.id()),
                        new Document("_id", "identity_incomplete:" + d.id())
                                .append("type", "identity_incomplete").append("product_id", d.id())
                                .append("vertical_id", d.verticalId()).append("status", "pending")
                                .append("created_at", new Date()),
                        new com.mongodb.client.model.ReplaceOptions().upsert(true)));
            }
            writePath.insertWithEvent(session, "products", productDoc, minted);
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
