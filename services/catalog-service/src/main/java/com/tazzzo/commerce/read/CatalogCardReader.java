package com.tazzzo.commerce.read;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.consumer.ConsumerEligibility;
import org.bson.Document;

import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link CatalogCardReadPort}: reads one product document and gates it through
 * {@link ConsumerEligibility#isEligible(Document)} — the single ratified predicate (its javadoc
 * explicitly offers in-memory evaluation "for any caller holding the document"; a second
 * near-copy is the drift this reuse avoids). Membership is CURRENT by REL-MEM-1 — release scopes
 * taxonomy, not per-product eligibility — so no release parameter belongs here.
 *
 * <p>At launch {@code skuId == productId}, so the lookup key is the product {@code _id}; when
 * variants introduce SKU documents, ONLY this reader changes — the port and the projection
 * schema already carry both ids.
 *
 * <p>PR-14B — also implements {@link TransactionalCatalogCardReadPort}: the session-aware
 * companion a future {@code customer.order} composes through, reusing the EXACT same
 * {@link #toFacts} mapping and eligibility predicate via {@link #lookup}. Only the Mongo call
 * shape differs.
 */
public class CatalogCardReader implements CatalogCardReadPort, TransactionalCatalogCardReadPort {

    private final MongoDatabase db;

    public CatalogCardReader(MongoDatabase db) {
        this.db = Objects.requireNonNull(db);
    }

    @Override
    public Optional<CatalogCardFacts> findEligibleCard(String skuId) {
        if (skuId == null || skuId.isBlank()) {
            return Optional.empty();
        }
        Document p = db.getCollection("products").find(Filters.eq("_id", skuId)).first();
        return lookup(skuId, p);
    }

    @Override
    public Optional<CatalogCardFacts> findEligibleCard(ClientSession session, String skuId) {
        if (skuId == null || skuId.isBlank()) {
            return Optional.empty();
        }
        Document p = db.getCollection("products").find(session, Filters.eq("_id", skuId)).first();
        return lookup(skuId, p);
    }

    private static Optional<CatalogCardFacts> lookup(String skuId, Document p) {
        if (p == null || !ConsumerEligibility.isEligible(p)) {
            return Optional.empty();
        }
        return Optional.of(toFacts(skuId, p));
    }

    private static CatalogCardFacts toFacts(String skuId, Document p) {
        Document classification = p.get("classification", Document.class);
        return new CatalogCardFacts(
                skuId,
                skuId, // launch: product _id IS the SKU id; carried separately by design
                p.getString("title"),
                p.getString("brand_code"),
                classification == null ? null : classification.getString("vertical_id"),
                p.get("version") == null ? 0L : ((Number) p.get("version")).longValue());
    }
}
