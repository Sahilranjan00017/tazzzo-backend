package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.domain.PackOf;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.schema.CanonicalKeyService;

import com.mongodb.MongoWriteException;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * F-5 — variant_pack write. Before this existed, product_type="variant_pack" fell through to
 * MintService and was minted as an ordinary single: the type was decorative and a 6x250ml
 * multipack was indistinguishable from a 1500ml bottle.
 *
 * Component liveness is checked IN the transaction (C-2), exactly as BundleService does:
 * the component must be lifecycle=active and must not itself be a bundle or a variant_pack
 * (no nesting — V1 policy, mirroring the bundle rule).
 *
 * Governance runs first, as in MintService: the I-6 bypass closes at the service tier.
 */
@Service
public class VariantPackService {

    private final Tx tx;
    private final WritePath writePath;
    private final AttributeGovernanceService governance;
    private final CanonicalKeyService canonicalKeys;

    public VariantPackService(Tx tx, WritePath writePath, AttributeGovernanceService governance,
                              CanonicalKeyService canonicalKeys) {
        this.tx = tx;
        this.writePath = writePath;
        this.governance = governance;
        this.canonicalKeys = canonicalKeys;
    }

    public void writeVariantPack(ProductDraft draft) {
        if (!"variant_pack".equals(draft.productType())) {
            throw new IllegalArgumentException("draft is not a variant_pack");
        }
        PackOf packOf = draft.packOf();
        if (packOf == null || packOf.componentProductId() == null) {
            throw new VariantPackException("variant_pack requires pack_of");
        }
        // qty >= 2 is also a validator rule; rejected here so the API returns a coded 422
        // rather than a raw write error, and so the reason names the field.
        if (packOf.qty() < 2) {
            throw new VariantPackException("pack_of.qty must be >= 2 (a pack of 1 is a single)");
        }
        governance.validate(draft.verticalId(),
                draft.attributes() == null ? Map.of() : draft.attributes(), draft.evidenceRefs());
        tx.run(session -> {
            Document c = writePath.database().getCollection("products")
                    .find(session, Filters.eq("_id", packOf.componentProductId())).first();
            if (c == null || !"active".equals(c.getString("lifecycle"))) {
                throw new VariantPackException(
                        "pack_of component not active: " + packOf.componentProductId());
            }
            String componentType = c.getString("product_type");
            if ("bundle".equals(componentType) || "variant_pack".equals(componentType)) {
                throw new VariantPackException(
                        "pack_of component must be a single (no nesting): "
                                + packOf.componentProductId());
            }
            EventPayload packed = new EventPayload("VARIANT_PACK_LINKED", draft.id(),
                    Map.of("component", packOf.componentProductId(), "qty", packOf.qty()));
            // CAT-ID + U-4-h: the pack's key is self-derived from its OWN total plus packof=N,
            // so 6x250ml (pack=1500ml|packof=6) never collides with a 1500ml single.
            Optional<String> canonicalKey = canonicalKeys.derive(draft.verticalId(),
                    draft.brandCode(), draft.productType(),
                    draft.attributes() == null ? Map.of() : draft.attributes(), packOf);
            Document productDoc = ProductDocuments.fromDraft(draft);
            if (canonicalKey.isPresent()) {
                ProductDocuments.applyCanonicalKey(productDoc, canonicalKey.get(),
                        CanonicalKeyService.KEY_VERSION);
                try {
                    writePath.auxWrite(session, "canonical_keys", packed, coll -> coll.insertOne(session,
                            new Document("_id", canonicalKey.get()).append("product_id", draft.id())
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
                writePath.auxWrite(session, "work_queue", packed, coll -> coll.replaceOne(session,
                        new Document("_id", "identity_incomplete:" + draft.id()),
                        new Document("_id", "identity_incomplete:" + draft.id())
                                .append("type", "identity_incomplete").append("product_id", draft.id())
                                .append("vertical_id", draft.verticalId()).append("status", "pending")
                                .append("created_at", new Date()),
                        new com.mongodb.client.model.ReplaceOptions().upsert(true)));
            }
            writePath.insertWithEvent(session, "products", productDoc, packed);
        });
    }
}
