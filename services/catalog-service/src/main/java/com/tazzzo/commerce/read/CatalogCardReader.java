package com.tazzzo.commerce.read;

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
 */
public class CatalogCardReader implements CatalogCardReadPort {

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
        if (p == null || !ConsumerEligibility.isEligible(p)) {
            return Optional.empty();
        }
        Document classification = p.get("classification", Document.class);
        return Optional.of(new CatalogCardFacts(
                skuId,
                skuId, // launch: product _id IS the SKU id; carried separately by design
                p.getString("title"),
                p.getString("brand_code"),
                classification == null ? null : classification.getString("vertical_id"),
                p.get("version") == null ? 0L : ((Number) p.get("version")).longValue()));
    }
}
