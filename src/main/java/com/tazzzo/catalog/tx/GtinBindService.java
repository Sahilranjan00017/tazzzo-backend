package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/** T5 — GTIN binding: registry (SoT) + product cache in ONE transaction (M13). */
@Service
public class GtinBindService {

    private final Tx tx;
    private final WritePath writePath;

    public GtinBindService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void bind(String productId, String gtin, String market) {
        tx.run(session -> {
            EventPayload bound = new EventPayload("GTIN_BOUND", productId,
                    Map.of("gtin", gtin, "market", market));
            // close any open binding for this market, then open the new one
            writePath.auxWrite(session, "gtin_registry", bound, c -> c.updateOne(session,
                    Filters.eq("_id", gtin),
                    Updates.set("bindings.$[open].to", new Date()),
                    new UpdateOptions().arrayFilters(java.util.List.of(
                            Filters.and(Filters.eq("open.market", market), Filters.eq("open.to", null))))));
            writePath.auxWrite(session, "gtin_registry", bound, c -> c.updateOne(session,
                    Filters.eq("_id", gtin),
                    Updates.push("bindings", new Document("product_id", productId)
                            .append("market", market).append("from", new Date()).append("to", null)),
                    new UpdateOptions().upsert(true)));
            Document product = writePath.database().getCollection("products")
                    .find(session, Filters.eq("_id", productId)).first();
            if (product == null) throw new ProductNotFoundException(productId);
            writePath.casUpdateWithEvent(session, "products", productId, product.getInteger("version"),
                    Updates.combine(Updates.push("gtins", new Document("value", gtin)
                                    .append("market", market).append("valid_from", new Date()).append("valid_to", null)),
                            Updates.inc("version", 1)), bound);
        });
    }
}
