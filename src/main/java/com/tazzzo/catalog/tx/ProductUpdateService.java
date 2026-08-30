package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * The domain owner of product edits (review M1: this logic previously lived in the
 * controller, which made the transport layer a second write path). Lifecycle rules live
 * here: a product mid-merge or terminal may not be edited.
 */
@Service
public class ProductUpdateService {

    private static final Set<String> EDITABLE = Set.of("draft", "active", "discontinued");

    private final Tx tx;
    private final WritePath writePath;

    public ProductUpdateService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void updateTitle(String productId, int expectedVersion, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        tx.run(session -> {
            Document p = writePath.database().getCollection("products")
                    .find(session, Filters.eq("_id", productId)).first();
            if (p == null) {
                throw new ProductNotFoundException(productId);
            }
            String lifecycle = p.getString("lifecycle");
            if (!EDITABLE.contains(lifecycle)) {
                throw new ProductStateException("product is " + lifecycle + "; not editable");
            }
            writePath.casUpdateWithEvent(session, "products", productId, expectedVersion,
                    Updates.combine(Updates.set("title", title),
                            Updates.set("updated_at", new Date()), Updates.inc("version", 1)),
                    new EventPayload("PRODUCT_TITLE_UPDATED", productId, Map.of("title", title)));
        });
    }
}
