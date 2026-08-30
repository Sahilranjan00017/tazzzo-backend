package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.domain.PackOf;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
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

    public VariantPackService(Tx tx, WritePath writePath, AttributeGovernanceService governance) {
        this.tx = tx;
        this.writePath = writePath;
        this.governance = governance;
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
            writePath.insertWithEvent(session, "products", ProductDocuments.fromDraft(draft), packed);
        });
    }
}
