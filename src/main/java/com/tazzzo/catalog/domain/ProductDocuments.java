package com.tazzzo.catalog.domain;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Builds validator-conformant product documents (see docs/contract_attack.js). */
public final class ProductDocuments {

    private ProductDocuments() { }

    public static Document fromDraft(ProductDraft d) {
        Document identity = new Document("type", d.identityType());
        if ("internal".equals(d.identityType())) {
            identity.append("internal_key", d.internalKey());
        }
        Document classification = new Document()
                .append("vertical_id", d.verticalId())
                .append("release_id", d.releaseId())
                .append("status", d.classificationStatus())
                .append("method_detail", new Document())
                .append("evidence_refs", d.evidenceRefs() == null ? List.of() : d.evidenceRefs());
        Document doc = new Document()
                .append("_id", d.id())
                .append("product_type", d.productType())
                .append("identity", identity)
                .append("brand_code", d.brandCode())
                .append("title", d.title())
                .append("lifecycle", "draft")
                .append("classification", classification)
                .append("attributes", d.attributes() == null ? new Document() : new Document(d.attributes()))
                .append("attributes_meta", new Document("validated_release", d.releaseId()))
                .append("version", 1)
                .append("created_at", new Date());
        if (d.gtins() != null && !d.gtins().isEmpty()) {
            List<Document> gtins = new ArrayList<>();
            for (GtinBinding g : d.gtins()) {
                gtins.add(new Document("value", g.value()).append("market", g.market())
                        .append("valid_from", new Date()).append("valid_to", null));
            }
            doc.append("gtins", gtins);
        }
        if ("bundle".equals(d.productType())) {
            List<Document> contents = new ArrayList<>();
            for (BundleComponent c : d.bundleContents()) {
                Document comp = new Document("component_product_id", c.componentProductId())
                        .append("qty", c.qty());
                if (c.verticalIdSnapshot() != null) comp.append("vertical_id_snapshot", c.verticalIdSnapshot());
                contents.add(comp);
            }
            doc.append("bundle_contents", contents);
        }
        if ("variant_pack".equals(d.productType()) && d.packOf() != null) {
            doc.append("pack_of", new Document("component_product_id", d.packOf().componentProductId())
                    .append("qty", d.packOf().qty()));
        }
        return doc;
    }

    /**
     * CAT-ID-1: stamps the Catalogue-derived identity onto the product document. Derivation is
     * Catalogue's, never the caller's — the draft carries no canonical_key field, so a client
     * cannot propose one.
     */
    public static void applyCanonicalKey(Document productDoc, String key, String keyVersion) {
        productDoc.get("identity", Document.class)
                .append("canonical_key", key)
                .append("canonical_key_version", keyVersion);
    }

    public static Document eventDoc(String type, String productId, Map<String, Object> detail) {
        return new Document("type", type)
                .append("product_id", productId)
                .append("detail", new Document(detail))
                .append("at", new Date());
    }
}
