package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.domain.BundleComponent;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T7 — bundle write. Component liveness is checked IN the transaction (C-2): components must
 * be lifecycle=active and must not themselves be bundles (no nesting, V1 policy).
 */
@Service
public class BundleService {

    private final Tx tx;
    private final WritePath writePath;
    private final ProductLifecycleService lifecycle;

    public BundleService(Tx tx, WritePath writePath, ProductLifecycleService lifecycle) {
        this.tx = tx;
        this.writePath = writePath;
        this.lifecycle = lifecycle;
    }

    public void writeBundle(ProductDraft bundle) {
        if (!"bundle".equals(bundle.productType())) {
            throw new IllegalArgumentException("draft is not a bundle");
        }
        tx.run(session -> {
            EventPayload linked = new EventPayload("BUNDLE_LINKED", bundle.id(),
                    Map.of("components", bundle.bundleContents().size()));
            List<String> browse = new ArrayList<>();
            for (BundleComponent comp : bundle.bundleContents()) {
                Document c = writePath.database().getCollection("products")
                        .find(session, Filters.eq("_id", comp.componentProductId())).first();
                if (c == null || !"active".equals(c.getString("lifecycle"))
                        || "bundle".equals(c.getString("product_type"))) {
                    throw new BundleComponentException("component not active non-bundle: "
                            + comp.componentProductId());
                }
                String v = c.get("classification", Document.class).getString("vertical_id");
                if (v != null && !browse.contains(v)) browse.add(v);
            }
            Document doc = ProductDocuments.fromDraft(bundle);
            doc.append("browse_verticals", browse);
            writePath.insertWithEvent(session, "products", doc, linked);
        });
    }

    /**
     * Convenience used by fixtures/tests. Lifecycle is owned by ProductLifecycleService —
     * this only reads the current version and delegates, so there is exactly one
     * implementation of the transition rules.
     */
    public void activate(String productId) {
        Document p = writePath.database().getCollection("products")
                .find(Filters.eq("_id", productId)).first();
        if (p == null) throw new ProductNotFoundException(productId);
        lifecycle.activate(productId, p.getInteger("version"));
    }
}
