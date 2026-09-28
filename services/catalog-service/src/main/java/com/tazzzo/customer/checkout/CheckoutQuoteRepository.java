package com.tazzzo.customer.checkout;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-13A — {@code checkout_quotes}. Quotes are immutable once written and are NEVER deleted or
 * mutated by expiry (expiry is derived from {@code expiresAt}), so an expired quote stays
 * distinguishable from an unknown one. Every single-quote read is ownership-scoped
 * ({@code _id} AND {@code customerId}). The unique {@code (customerId, idempotencyKeyDigest)} index
 * (see SchemaBootstrap) is the concurrent-create guard.
 */
@Component
public class CheckoutQuoteRepository {

    public static final String COLLECTION = "checkout_quotes";

    private final MongoDatabase db;

    public CheckoutQuoteRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document findOwned(String quoteId, String customerId) {
        return collection().find(Filters.and(Filters.eq("_id", quoteId), Filters.eq("customerId", customerId)))
                .first();
    }

    public Document findByIdempotency(String customerId, String keyDigest) {
        return collection().find(idempotencyFilter(customerId, keyDigest)).first();
    }

    public Document findByIdempotency(ClientSession session, String customerId, String keyDigest) {
        return collection().find(session, idempotencyFilter(customerId, keyDigest)).first();
    }

    public void insert(ClientSession session, CheckoutQuote quote, String customerId, String keyDigest,
                       String fingerprint) {
        List<Document> items = new ArrayList<>(quote.lines().size());
        for (CheckoutQuote.Line l : quote.lines()) {
            items.add(new Document("skuId", l.skuId()).append("quantity", l.quantity())
                    .append("unitPricePaise", l.unitPricePaise()).append("lineTotalPaise", l.lineTotalPaise()));
        }
        collection().insertOne(session, new Document("_id", quote.quoteId()).append("customerId", customerId)
                .append("idempotencyKeyDigest", keyDigest).append("fingerprint", fingerprint)
                .append("cartVersion", quote.cartVersion()).append("addressId", quote.addressId())
                .append("items", items).append("itemCount", quote.itemCount())
                .append("subtotalPaise", quote.subtotalPaise()).append("currency", quote.currency())
                .append("createdAt", Date.from(quote.createdAt())).append("expiresAt", Date.from(quote.expiresAt())));
    }

    static CheckoutQuote toQuote(Document d) {
        List<CheckoutQuote.Line> lines = new ArrayList<>();
        for (Document i : d.getList("items", Document.class)) {
            lines.add(new CheckoutQuote.Line(i.getString("skuId"), i.get("quantity", Number.class).intValue(),
                    i.get("unitPricePaise", Number.class).longValue(),
                    i.get("lineTotalPaise", Number.class).longValue()));
        }
        return new CheckoutQuote(d.getString("_id"), d.get("cartVersion", Number.class).longValue(),
                d.getString("addressId"), List.copyOf(lines), d.get("itemCount", Number.class).intValue(),
                d.get("subtotalPaise", Number.class).longValue(), d.getString("currency"),
                d.getDate("createdAt").toInstant(), d.getDate("expiresAt").toInstant());
    }

    private static org.bson.conversions.Bson idempotencyFilter(String customerId, String keyDigest) {
        return Filters.and(Filters.eq("customerId", customerId), Filters.eq("idempotencyKeyDigest", keyDigest));
    }
}
