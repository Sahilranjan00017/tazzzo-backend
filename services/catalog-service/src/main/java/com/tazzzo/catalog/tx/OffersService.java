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

/**
 * Offer writes obey ordering law C-3: the price_events ledger append commits BEFORE the
 * offers_current upsert, same transaction — the direction whose crash residue is repairable
 * by replay (the reverse direction would be "repaired" into reverting a real price change).
 */
@Service
public class OffersService {

    private final Tx tx;
    private final WritePath writePath;

    public OffersService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public void upsertOffer(String productId, String source, String seller, String channel,
                            int price, boolean available) {
        tx.run(session -> {
            EventPayload e = new EventPayload("OFFER_UPDATED", productId,
                    Map.of("source", source, "seller", seller, "channel", channel, "price", price));
            writePath.auxWrite(session, "price_events", e, c -> c.insertOne(session,
                    new Document("product_id", productId).append("source", source)
                            .append("seller", seller).append("channel", channel)
                            .append("price", price).append("ts", new Date())));
            writePath.auxWrite(session, "offers_current", e, c -> c.updateOne(session,
                    Filters.and(Filters.eq("product_id", productId), Filters.eq("source", source),
                            Filters.eq("seller", seller), Filters.eq("channel", channel)),
                    Updates.combine(Updates.set("price", price), Updates.set("available", available),
                            Updates.set("last_seen_at", new Date())),
                    new UpdateOptions().upsert(true)));
        });
    }
}
