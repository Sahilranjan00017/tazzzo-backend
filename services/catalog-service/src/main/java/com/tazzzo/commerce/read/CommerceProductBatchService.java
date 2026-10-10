package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.tazzzo.catalog.consumer.ConsumerAdmissionGate;
import com.tazzzo.catalog.consumer.ConsumerEligibility;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.consumer.ConsumerReleaseResolver;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyScopeResolver;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounded public batch product read ({@code GET /v1/products:batch}). It answers, for a bounded list of
 * product ids, EXACTLY what {@code GET /v1/products/{id}} would say about each one - the same eligibility
 * predicate, the same release-scoped vertical reachability, the same projection-freshness rule
 * ({@link ProductDetailRuntimeComposer#chooseBase}), the same current-price overlay and the same enrichment
 * ({@link ProductCardRuntimeEnricher}) - but in a fixed number of reads, never one set per id:
 * <pre>
 *   1 release resolution   2 admission (1 + distinct ids)   3 ONE products find by _id $in
 *   4 ONE product_card_base find   5 ONE prices find   6 ONE serviceability resolution + ONE inventory batch
 * </pre>
 * plus at most one snapshot node read per distinct vertical for reachability. It returns the card (the part of
 * the product detail a rail renders), not the gallery or attributes.
 *
 * <p>An id that is unknown, not consumer-eligible (draft, unlisted, discontinued, unconfirmed, holding vertical,
 * non-admitted type), not reachable in the release, or MERGED away is simply absent from the cards and listed in
 * {@code missing}: all of those look identical, so the batch is no existence oracle. (A merged id is not
 * redirected to its survivor here - the single-id read does that; a caller holding a merged id re-asks the
 * survivor.) Infrastructure failure is never a miss: it propagates typed (503).
 */
public class CommerceProductBatchService {

    /** The enricher's page bound; the configurable cap can never exceed it. */
    public static final int HARD_MAX_IDS = ProductCardRuntimeEnricher.MAX_PAGE_SIZE;
    public static final int DEFAULT_MAX_IDS = HARD_MAX_IDS;
    /** The longest product id (ProductIds grammar: "TZP-" + 40) plus one separator. */
    private static final int ID_SLOT = 4 + 40 + 1;
    /** Room, beyond the ids, for release/pin/lat/lng and the parameter names in the raw query string. */
    private static final int QUERY_SLACK = 512;

    /** Exactly the fields the eligibility predicate and the card facts read. */
    private static final List<String> FIELDS = List.of("_id", "title", "brand_code", "product_type", "lifecycle",
            "classification.status", "classification.vertical_id", "version");

    public record Result(String resolvedReleaseId, List<RuntimeProductCard> cards, List<String> missing) { }

    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerTaxonomyScopeResolver scopes;
    private final ProductCardBaseReadPort bases;
    private final PriceReadPort prices;
    private final ProductCardRuntimeEnricher enricher;
    private final MongoDatabase db;
    private final int maxIds;

    public CommerceProductBatchService(ConsumerReleaseResolver releases, ConsumerAdmissionGate gate,
                                       ConsumerTaxonomyScopeResolver scopes, ProductCardBaseReadPort bases,
                                       PriceReadPort prices, ProductCardRuntimeEnricher enricher,
                                       MongoDatabase db, int maxIds) {
        if (maxIds < 1 || maxIds > HARD_MAX_IDS) {
            throw new IllegalStateException("tazzzo.commerce.product-batch.max-ids must be 1.." + HARD_MAX_IDS
                    + ", got " + maxIds);
        }
        this.releases = Objects.requireNonNull(releases);
        this.gate = Objects.requireNonNull(gate);
        this.scopes = Objects.requireNonNull(scopes);
        this.bases = Objects.requireNonNull(bases);
        this.prices = Objects.requireNonNull(prices);
        this.enricher = Objects.requireNonNull(enricher);
        this.db = Objects.requireNonNull(db);
        this.maxIds = maxIds;
    }

    public int maxIds() {
        return maxIds;
    }

    /** The longest {@code ids} value (separators included) a request within the cap can carry. */
    public int maxIdsParamLength() {
        return maxIds * ID_SLOT - 1;
    }

    /** The longest raw query string accepted: the ids at their longest, percent-encoded commas, and the slack. */
    public int maxQueryLength() {
        return "ids=".length() + maxIds * (ID_SLOT + 2) + QUERY_SLACK;
    }

    /** The one lookup filter, exposed so a test can explain exactly what is sent. */
    public static Bson lookupFilter(java.util.Collection<String> ids) {
        return Filters.in("_id", ids);
    }

    /**
     * @param requestedIds already validated against the id grammar and the cap by the transport; duplicates are
     *                     collapsed here, first occurrence wins, so the answer follows request order
     */
    public Result read(List<String> requestedIds, String explicitRelease, LocationQuery location,
                       ConsumerIdentity identity) {
        Objects.requireNonNull(location, "location required");
        LinkedHashSet<String> ids = new LinkedHashSet<>(requestedIds);
        if (ids.isEmpty() || requestedIds.size() > maxIds) {
            throw new IllegalArgumentException("ids must be 1.." + maxIds);
        }
        String release = releases.resolve(explicitRelease);                                    // 1
        gate.charge(ConsumerObservability.Route.COMMERCE_PRODUCTS_BATCH, identity, 1L + ids.size()); // 2
        return DomainReadGuard.guard(() -> compose(ids, release, location));
    }

    private Result compose(LinkedHashSet<String> ids, String release, LocationQuery location) {
        Map<String, Document> docs = new HashMap<>();
        for (Document d : db.getCollection("products").find(lookupFilter(ids))
                .projection(Projections.include(FIELDS))) {                                    // 3
            docs.put(d.getString("_id"), d);
        }
        // Eligible AND reachable, in request order; everything else is "missing" without a reason.
        Map<String, Boolean> reachableByVertical = new HashMap<>();
        LinkedHashMap<String, CatalogProductDetailFacts> visible = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Document doc = docs.get(id);
            if (doc == null || !ConsumerEligibility.isEligible(doc)) {
                missing.add(id);
                continue;
            }
            String vertical = doc.get("classification", Document.class).getString("vertical_id");
            if (!reachableByVertical.computeIfAbsent(vertical, v -> scopes.isReachableVertical(release, v))) {
                missing.add(id);
                continue;
            }
            visible.put(id, new CatalogProductDetailFacts(id, id, doc.getString("title"),
                    doc.getString("brand_code"), vertical,
                    doc.get("version") == null ? 0L : ((Number) doc.get("version")).longValue(), List.of()));
        }
        if (visible.isEmpty()) {
            return new Result(release, List.of(), List.copyOf(missing));
        }
        List<String> skuIds = List.copyOf(visible.keySet());
        Map<String, ProductCardBaseProjection> baseBySku = new HashMap<>();
        for (ProductCardBaseProjection b : bases.findBySkuIds(skuIds)) {                       // 4
            baseBySku.put(b.skuId(), b);
        }
        Map<String, PriceLookup> priceBySku = prices.findCurrentPrices(skuIds, Currency.INR); // 5
        List<ProductCardBaseProjection> composed = new ArrayList<>(skuIds.size());
        for (String sku : skuIds) {
            ProductCardBaseProjection base = ProductDetailRuntimeComposer.chooseBase(
                    Optional.ofNullable(baseBySku.get(sku)), visible.get(sku));
            composed.add(CurrentPriceOverlay.withCurrentPrice(base,
                    priceBySku.getOrDefault(sku, PriceLookup.missing())));
        }
        RuntimeProductPage page = enricher.enrichPage(composed, location);                     // 6
        return new Result(release, page.cards(), List.copyOf(missing));
    }
}
