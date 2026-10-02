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

    /**
     * PR-14B — loads the immutable, owned quote WITHOUT judging its temporal validity: unlike
     * {@code CheckoutService.readQuote} (the customer-facing read, which DOES enforce
     * {@code isExpired}), this exists for a caller — a future {@code customer.order} — whose OWN
     * transaction must be the expiry authority. A durable, already-committed Order must be able to
     * replay successfully even after its source quote has since expired; if this method enforced
     * expiry too, Order could never reach its own existing-order check first. Reuses the existing
     * {@link #findOwned} + {@link #toQuote} — no duplicated Mongo decoding. Returns {@code null} when
     * the quote is unknown/foreign, the same convention {@link #findOwned} already follows.
     */
    public CheckoutQuote findOwnedQuote(String quoteId, String customerId) {
        Document d = findOwned(quoteId, customerId);
        return d == null ? null : toQuote(d);
    }

    public Document findByIdempotency(String customerId, String keyDigest) {
        return collection().find(idempotencyFilter(customerId, keyDigest)).first();
    }

    public Document findByIdempotency(ClientSession session, String customerId, String keyDigest) {
        return collection().find(session, idempotencyFilter(customerId, keyDigest)).first();
    }

    public void insert(ClientSession session, CheckoutQuote quote, String customerId, String keyDigest,
                       String fingerprint) {
        collection().insertOne(session, toDocument(quote, customerId, keyDigest, fingerprint));
    }

    static Document toDocument(CheckoutQuote quote, String customerId, String keyDigest, String fingerprint) {
        List<Document> items = new ArrayList<>(quote.lines().size());
        for (CheckoutQuote.Line l : quote.lines()) {
            items.add(new Document("skuId", l.skuId()).append("quantity", l.quantity())
                    .append("unitPricePaise", l.unitPricePaise()).append("lineTotalPaise", l.lineTotalPaise()));
        }
        Document d = new Document("_id", quote.quoteId()).append("customerId", customerId)
                .append("idempotencyKeyDigest", keyDigest).append("fingerprint", fingerprint)
                .append("cartVersion", quote.cartVersion()).append("addressId", quote.addressId())
                .append("addressVersion", quote.addressVersion())
                .append("items", items).append("itemCount", quote.itemCount())
                .append("subtotalPaise", quote.subtotalPaise()).append("currency", quote.currency())
                .append("createdAt", Date.from(quote.createdAt())).append("expiresAt", Date.from(quote.expiresAt()));
        if (quote.benefitSnapshot() != null) { // absent ONLY on a legacy quote; never written as null/placeholder
            d.append(CheckoutBenefitSnapshotCodec.FIELD, CheckoutBenefitSnapshotCodec.toDocument(quote.benefitSnapshot()));
        }
        if (quote.moneySnapshot() != null) { // absent ONLY on a legacy quote; never written as null/placeholder
            d.append(CheckoutMoneySnapshotCodec.FIELD, CheckoutMoneySnapshotCodec.toDocument(quote.moneySnapshot()));
        }
        return d;
    }

    /**
     * PR-13B — {@code addressVersion} has NO fallback/default: a row written before this field
     * existed (or corrupted to drop it) is a data-integrity defect, not "version 0". It fails LOUD
     * here (never silently defaulted), the same convention every other invariant in
     * {@link CheckoutQuote}'s compact constructor already follows; the public boundary maps that to
     * a safe 500 INTERNAL, never a 200/404/410/503 (see {@code CheckoutService}).
     */
    static CheckoutQuote toQuote(Document d) {
        List<CheckoutQuote.Line> lines = new ArrayList<>();
        for (Document i : d.getList("items", Document.class)) {
            lines.add(new CheckoutQuote.Line(i.getString("skuId"), i.get("quantity", Number.class).intValue(),
                    i.get("unitPricePaise", Number.class).longValue(),
                    i.get("lineTotalPaise", Number.class).longValue()));
        }
        Number addressVersion = d.get("addressVersion", Number.class);
        if (addressVersion == null) {
            throw new IllegalArgumentException("checkout quote missing addressVersion (legacy or corrupt row)");
        }
        // PRESENT => strictly reconstructed (an explicit null is corruption); ABSENT => a legacy quote, never "no
        // benefit" and never an error (legacy quotes are never deleted, so a required field would turn an old
        // expired quote's 410 into a 500 before expiry handling)
        CheckoutBenefitSnapshot benefitSnapshot = d.containsKey(CheckoutBenefitSnapshotCodec.FIELD)
                ? CheckoutBenefitSnapshotCodec.fromDocument(d.get(CheckoutBenefitSnapshotCodec.FIELD)) : null;
        // same rule for money: PRESENT => strictly reconstructed, ABSENT => legacy quote (never a synthesized payable)
        CheckoutMoneySnapshot moneySnapshot = d.containsKey(CheckoutMoneySnapshotCodec.FIELD)
                ? CheckoutMoneySnapshotCodec.fromDocument(d.get(CheckoutMoneySnapshotCodec.FIELD)) : null;
        return new CheckoutQuote(d.getString("_id"), d.get("cartVersion", Number.class).longValue(),
                d.getString("addressId"), addressVersion.longValue(), List.copyOf(lines),
                d.get("itemCount", Number.class).intValue(), d.get("subtotalPaise", Number.class).longValue(),
                d.getString("currency"), d.getDate("createdAt").toInstant(), d.getDate("expiresAt").toInstant(),
                benefitSnapshot, moneySnapshot);
    }

    private static org.bson.conversions.Bson idempotencyFilter(String customerId, String keyDigest) {
        return Filters.and(Filters.eq("customerId", customerId), Filters.eq("idempotencyKeyDigest", keyDigest));
    }
}
