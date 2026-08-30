package com.tazzzo.catalog.tx;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Service;

/**
 * F1 fix: the read boundary. Transport must never hold a MongoDatabase — otherwise a future
 * developer reasonably concludes that writes are acceptable there too, which is exactly how
 * the Step-3 second write path appeared. Reads live here; writes live in the tx services.
 */
@Service
public class ProductQueryService {

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

    public Document findRelease(String releaseId) {
        return db.getCollection("catalogue_releases").find(Filters.eq("_id", releaseId)).first();
    }
}
