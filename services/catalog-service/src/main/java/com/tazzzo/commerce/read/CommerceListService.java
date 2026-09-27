package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.catalog.consumer.ConsumerAdmissionGate;
import com.tazzzo.catalog.consumer.ConsumerCursorCodec;
import com.tazzzo.catalog.consumer.ConsumerEligibility;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.consumer.ConsumerReleaseResolver;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyScopeResolver;
import com.tazzzo.catalog.consumer.ConsumerVisibilityProbe;
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import com.tazzzo.pricing.PriceStatus;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The public commerce category-products list (PR-10B). Reuses the ratified consumer SEAMS —
 * release resolution, TAX-REACH-1 reachability, release-bound scope, {@code 1 + page_size}
 * admission, the PARENT visibility probe, {@code ConsumerEligibility.within(scope)} keyset
 * membership on {@code _id ASC} — and swaps ONLY the projection: instead of the consumer attribute
 * projector, it reads {@code product_card_base} rows, overlays CURRENT canonical price
 * ({@link CurrentPriceOverlay}) so no stale price is served, and enriches ONE page through
 * {@link ProductCardRuntimeEnricher} (one serviceability resolution, one batched inventory read).
 *
 * <p><b>Freshness readiness gate (PR-10B review §31):</b> lists require a production-managed
 * projection. If projection freshness is not enabled this endpoint fails closed (503) rather than
 * serve unmanaged rows. PDP fails closed through its own composition and does not need this gate.
 *
 * <p><b>Missing base row (§15):</b> a product selected by authoritative membership but with no
 * projection row is a freshness gap. It is NOT fabricated and NOT silently dropped (which would
 * corrupt cursor cardinality): a fail-closed card is composed from the FRESH membership catalog
 * facts (identity only, {@code PriceStatus.MISSING}, no thumbnail, not buyable) and a
 * {@code commerce_list_base_missing} signal is logged. If those facts cannot satisfy the projection
 * technical bounds, the PAGE fails closed (503) rather than mutate pagination.
 *
 * <p>Read-only: no {@code rebuildOne}, no writes. Commerce cursors are signed with a distinct route
 * so they can never be replayed across the {@code /catalog/v1} and {@code /v1} surfaces.
 */
public class CommerceListService {

    private static final Logger log = LoggerFactory.getLogger(CommerceListService.class);
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    private static final List<String> PAGE_FIELDS =
            List.of("_id", "title", "brand_code", "classification.vertical_id");

    private final SnapshotTaxonomyReader snapshots;
    private final ConsumerTaxonomyScopeResolver scopes;
    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerVisibilityProbe probe;
    private final ConsumerCursorCodec cursors;              // commerce-route instance
    private final ProductCardBaseReader baseReader;
    private final PriceReadPort prices;
    private final ProductCardRuntimeEnricher enricher;
    private final MongoDatabase db;
    private final boolean freshnessReady;

    public CommerceListService(SnapshotTaxonomyReader snapshots, ConsumerTaxonomyScopeResolver scopes,
                               ConsumerReleaseResolver releases, ConsumerAdmissionGate gate,
                               ConsumerVisibilityProbe probe, ConsumerCursorCodec cursors,
                               ProductCardBaseReader baseReader, PriceReadPort prices,
                               ProductCardRuntimeEnricher enricher, MongoDatabase db,
                               boolean freshnessReady) {
        this.snapshots = snapshots;
        this.scopes = scopes;
        this.releases = releases;
        this.gate = gate;
        this.probe = probe;
        this.cursors = cursors;
        this.baseReader = baseReader;
        this.prices = prices;
        this.enricher = enricher;
        this.db = db;
        this.freshnessReady = freshnessReady;
    }

    public CommerceProductPage list(String nodeId, String explicitRelease, String pageSizeParam,
                                    String cursorParam, LocationQuery location, ConsumerIdentity identity) {
        if (!freshnessReady) {
            throw new ConsumerFailures.Unavailable("projection freshness not enabled");
        }
        cursors.requireReady();

        ConsumerCursorCodec.ListCursor cursor = null;
        int pageSize;
        String release;
        if (cursorParam != null) {
            cursor = cursors.decode(cursorParam);
            if (!cursor.nodeId().equals(nodeId)) {
                throw new ConsumerFailures.InvalidCursor("cursor node");
            }
            if (explicitRelease != null && !explicitRelease.isBlank()
                    && !explicitRelease.trim().equals(cursor.releaseId())) {
                throw new ConsumerFailures.InvalidCursor("cursor release");
            }
            if (pageSizeParam != null && parsePageSize(pageSizeParam) != cursor.pageSize()) {
                throw new ConsumerFailures.InvalidCursor("cursor page size");
            }
            if (cursor.pageSize() > MAX_PAGE_SIZE) {
                throw new ConsumerFailures.InvalidCursor("cursor page size above maximum");
            }
            pageSize = cursor.pageSize();
            release = releases.resolve(cursor.releaseId());
        } else {
            pageSize = pageSizeParam == null ? DEFAULT_PAGE_SIZE : validatedPageSize(pageSizeParam);
            release = releases.resolve(explicitRelease);
        }

        Document requested = snapshots.node(release, nodeId);
        if (!scopes.isReachable(release, requested)) {
            gate.charge(ConsumerObservability.Route.COMMERCE_LIST, identity, 1);
            throw new ConsumerFailures.NotFound("node not consumer-reachable: " + nodeId);
        }
        List<String> scope = scopes.scope(release, nodeId);
        gate.charge(ConsumerObservability.Route.COMMERCE_LIST, identity, 1L + pageSize);

        if (!probe.hasEligibleProduct(ConsumerObservability.Route.COMMERCE_LIST,
                ConsumerObservability.ProbeScope.PARENT, scope)) {
            throw new ConsumerFailures.NotFound("node consumer-empty: " + nodeId);
        }

        Bson filter = cursor == null
                ? ConsumerEligibility.within(scope)
                : Filters.and(ConsumerEligibility.within(scope), Filters.gt("_id", cursor.lastProductId()));
        List<Document> fetched = db.getCollection("products").find(filter)
                .projection(Projections.include(PAGE_FIELDS)).sort(Sorts.ascending("_id"))
                .limit(pageSize + 1).into(new ArrayList<>());
        boolean more = fetched.size() > pageSize;
        List<Document> pageItems = more ? fetched.subList(0, pageSize) : fetched;

        // Base rows for the page, overlaid with CURRENT canonical price; missing rows fail closed.
        List<String> skuIds = pageItems.stream().map(d -> d.getString("_id")).toList();
        Map<String, ProductCardBaseProjection> baseBySku = new LinkedHashMap<>();
        for (ProductCardBaseProjection b : baseReader.findBySkuIds(skuIds)) {
            baseBySku.put(b.skuId(), b);
        }
        Map<String, PriceLookup> priceBySku = prices.findCurrentPrices(skuIds, Currency.INR);

        List<ProductCardBaseProjection> bases = new ArrayList<>(pageItems.size());
        for (Document item : pageItems) {
            String sku = item.getString("_id");
            ProductCardBaseProjection base = baseBySku.get(sku);
            if (base != null) {
                bases.add(CurrentPriceOverlay.withCurrentPrice(base,
                        priceBySku.getOrDefault(sku, PriceLookup.missing())));
            } else {
                // freshness gap: fail-closed card from FRESH membership facts, not fabricated.
                log.warn("commerce_list_base_missing sku={}", sku);
                bases.add(failClosedBase(item));
            }
        }

        RuntimeProductPage runtimePage = enricher.enrichPage(bases, location);

        String next = null;
        if (more) {
            String last = pageItems.get(pageItems.size() - 1).getString("_id");
            next = cursors.encode(new ConsumerCursorCodec.ListCursor(nodeId, release, pageSize, last));
        }
        return new CommerceProductPage(release, runtimePage, next);
    }

    /**
     * Fail-closed stand-in for a membership product whose projection row is missing: fresh Catalog
     * identity from the page read, {@code PriceStatus.MISSING} (no price → not buyable), no media
     * key. Never overlaid with price — a freshness gap must not sell. Over-bound facts (which the
     * projection could never represent) fail the PAGE closed rather than corrupt pagination.
     */
    private ProductCardBaseProjection failClosedBase(Document item) {
        Document classification = item.get("classification", Document.class);
        String sku = item.getString("_id");
        try {
            return new ProductCardBaseProjection(sku, sku, item.getString("title"),
                    item.getString("brand_code"),
                    classification == null ? null : classification.getString("vertical_id"),
                    PriceStatus.MISSING, null, null, null, null, 0L, null, null, 1L);
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.Unavailable("projection facts over bounds for " + sku);
        }
    }

    private static int validatedPageSize(String raw) {
        int value = parsePageSize(raw);
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new ConsumerFailures.InvalidRequest("page_size out of range");
        }
        return value;
    }

    private static int parsePageSize(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConsumerFailures.InvalidRequest("page_size is not an integer");
        }
    }
}
