package com.tazzzo.catalog.tx;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * F1 fix: the read boundary. Transport must never hold a MongoDatabase — otherwise a future
 * developer reasonably concludes that writes are acceptable there too, which is exactly how
 * the Step-3 second write path appeared. Reads live here; writes live in the tx services.
 */
@Service
public class ProductQueryService {

    private static final String LIFECYCLE_FIELD = "lifecycle";
    private static final String CLASSIFICATION_STATUS_FIELD = "classification.status";

    private final MongoDatabase db;

    public ProductQueryService(MongoDatabase db) {
        this.db = db;
    }

    /** @throws ProductNotFoundException when the product does not exist (404 at the API). */
    public Document requireProduct(String productId) {
        Document p = db.getCollection("products").find(Filters.eq("_id", productId)).first();
        if (p == null) throw new ProductNotFoundException(productId);
        return p;
    }

    /**
     * CAT-ID-4 — identity resolution by canonical key. This is the lookup that lets a second
     * source find a product it never created, WITHOUT any crawler-side memory: the key is
     * recomputed from the observation and resolved against Catalogue truth.
     */
    public Document findByCanonicalKey(String canonicalKey) {
        Document row = db.getCollection("canonical_keys")
                .find(Filters.eq("_id", canonicalKey)).first();
        if (row == null) throw new ProductNotFoundException(canonicalKey);
        return requireProduct(row.getString("product_id"));
    }

    /**
     * One page of product summaries in ascending id order, strictly after {@code afterId}. {@code lifecycle} and
     * {@code classificationStatus} are only meaningful together with a vertical (the caller enforces that), so every query
     * is either an {@code _id} range scan or served by the {@code (classification.vertical_id, lifecycle,
     * classification.status, _id)} index -- never a collection scan on a secondary filter.
     */
    public List<Document> list(String verticalId, String lifecycle, String classificationStatus, String afterId, int limit) {
        // Equality on CALLER-SUPPLIED values, driven by a field table. This is an admin inspection filter, not the consumer
        // eligibility predicate (ConsumerEligibility owns that and is the only place that fixes lifecycle/status values).
        List<org.bson.conversions.Bson> filters = new java.util.ArrayList<>();
        Map<String, String> byField = new java.util.LinkedHashMap<>();
        byField.put("classification.vertical_id", verticalId);
        byField.put(LIFECYCLE_FIELD, lifecycle);
        byField.put(CLASSIFICATION_STATUS_FIELD, classificationStatus);
        byField.forEach((field, value) -> {
            if (value != null) filters.add(Filters.eq(field, value));
        });
        if (afterId != null) filters.add(Filters.gt("_id", afterId));
        org.bson.conversions.Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        List<Document> out = new java.util.ArrayList<>();
        db.getCollection("products").find(filter)
                .projection(com.mongodb.client.model.Projections.include("_id", "product_type", "lifecycle", "brand_code", "title",
                        "classification", "version"))
                .sort(com.mongodb.client.model.Sorts.ascending("_id")).limit(limit).into(out);
        return out;
    }

    public Document findRelease(String releaseId) {
        return db.getCollection("catalogue_releases").find(Filters.eq("_id", releaseId)).first();
    }
}
