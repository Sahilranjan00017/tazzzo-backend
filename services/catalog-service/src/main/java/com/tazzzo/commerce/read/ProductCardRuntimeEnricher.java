package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryReadPort;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.serviceability.ServiceabilityReadPort;
import com.tazzzo.serviceability.ServiceabilityResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The runtime composer (PR-08): converts GLOBAL {@link ProductCardBaseProjection} rows plus one
 * request's {@link LocationQuery} into INTERNAL {@link RuntimeProductCard}s — the first place
 * global data meets location state, so CROSS-LOCATION CORRECTNESS is the primary boundary here.
 *
 * <p><b>Stateless and pure (STEP 27):</b> the only fields are injected, immutable ports. No
 * static mutable state, no ThreadLocal, no "current store" — every request context flows through
 * arguments, so one user's stock can never contaminate another's.
 *
 * <p><b>One serviceability resolution per request (STEP 7)</b> shared by every card in the page;
 * <b>ONE batched inventory read (STEP 19)</b> for the whole page at the resolved internal
 * fulfillment location. That internal id lives only in local scope — it appears in no output
 * type (reflection-guard tested).
 *
 * <p><b>Enrichment is ONE-WAY (STEP 4):</b> nothing here writes anywhere. No persistence, no
 * cache (PR-08 proves correctness first; a future cache key must come from routing topology —
 * serviceAreaId alone is INSUFFICIENT because two PINs sharing an area may route to different
 * fulfillment locations; PR-10 owns that design).
 *
 * <p><b>Projection freshness gate unchanged (STEP 24):</b> this layer is INTERNAL — no
 * controller, no request path. product_card_base stays NOT LIVE until an explicit freshness
 * mechanism ships; per-request rebuildOne fan-out is explicitly NOT the answer.
 *
 * <p><b>Failure policy (STEP 26):</b> normal absence (unserviceable PIN, missing inventory row,
 * unconfigured media base) degrades the card safely with buyable=false; INFRASTRUCTURE
 * exceptions from any port propagate typed and untouched — an outage is never disguised as
 * "out of stock" or "unserviceable". Buyability always fails closed.
 *
 * <p><b>Hooks:</b> commerce_enrich_anonymous, commerce_enrich_serviceable,
 * commerce_enrich_unserviceable, commerce_inventory_missing, commerce_inventory_inactive,
 * commerce_media_unconfigured, commerce_enrich_failure. PINs are logged masked only.
 */
public class ProductCardRuntimeEnricher {

    private static final Logger log = LoggerFactory.getLogger(ProductCardRuntimeEnricher.class);

    /**
     * FROZEN LAUNCH SEAM (STEP 11): the repository audit found NO catalog-owned order-quantity
     * policy, so the catalog cap is ABSENT (unbounded) and the effective card maximum is exactly
     * {@code inventory.effectivePurchasableQuantity()}. This does NOT make Inventory the owner of
     * future business policy — when a catalog policy source exists, the formula becomes
     * {@code min(catalogPolicy, inventoryEffective)} here, with no schema change. Minimum stays
     * the frozen API default of 1 for the same reason.
     */
    static final int MINIMUM_ORDER_QUANTITY = 1;

    /**
     * PAGE BOUND (PR-08 review, STEP 6): the batch-query design is justified by "page <= 50
     * bounded index seeks", so this boundary ENFORCES that cardinality — an oversized page is
     * rejected typed BEFORE any serviceability/inventory/media work. The bound lives here in
     * commerce.read, deliberately NOT on InventoryReadPort, which may serve other legitimate
     * batch consumers later.
     */
    static final int MAX_PAGE_SIZE = 50;

    private final ServiceabilityReadPort serviceability;
    private final InventoryReadPort inventory;
    private final MediaUrlResolver mediaUrls;

    public ProductCardRuntimeEnricher(ServiceabilityReadPort serviceability,
                                      InventoryReadPort inventory,
                                      MediaUrlResolver mediaUrls) {
        this.serviceability = Objects.requireNonNull(serviceability);
        this.inventory = Objects.requireNonNull(inventory);
        this.mediaUrls = Objects.requireNonNull(mediaUrls);
    }

    /** Convenience single-card composition (PDP seam for PR-09) — same rules as a page of one. */
    public RuntimeProductCard enrichOne(ProductCardBaseProjection base, LocationQuery location) {
        return enrichPage(List.of(base), location).cards().get(0);
    }

    /**
     * Enrich one page of base cards for one request. Input order is preserved. Serviceability is
     * resolved ONCE; inventory is read ONCE (batch) and only when the location is SERVICEABLE.
     */
    public RuntimeProductPage enrichPage(List<ProductCardBaseProjection> bases, LocationQuery location) {
        Objects.requireNonNull(bases, "bases required");
        Objects.requireNonNull(location, "location required");
        if (bases.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page exceeds MAX_PAGE_SIZE=" + MAX_PAGE_SIZE
                    + ": " + bases.size());
        }
        // EMPTY-PAGE SEMANTICS (STEP 9, deliberate — option A): a PIN request with zero cards
        // STILL resolves serviceability so the page carries service-area context (the frozen
        // PagedProductResponse echoes serviceArea for empty categories); the inventory batch is
        // skipped below because there is nothing to enrich.
        try {
            if (!location.isPresent()) {
                log.debug("commerce_enrich_anonymous cards={}", bases.size());
                return new RuntimeProductPage(composeWithoutInventory(bases, null), null);
            }
            Pincode pin = location.pincode().orElseThrow(() -> new UnsupportedLocationException(
                    "lat/lng location resolution is reserved but not implemented; supply a PIN"));

            // ONE resolution per request (STEP 7), shared by every card below.
            ServiceabilityResolution resolution = serviceability.resolveByPincode(pin);
            switch (resolution.status()) {
                case SERVICEABLE -> {
                    log.debug("commerce_enrich_serviceable pin={} cards={}", mask(pin), bases.size());
                    return new RuntimeProductPage(
                            composeServiceable(bases, resolution.fulfillmentLocationId()),
                            new RuntimeServiceArea(resolution.serviceAreaId(), true));
                }
                case UNSERVICEABLE -> {
                    log.info("commerce_enrich_unserviceable pin={} reason=UNSERVICEABLE", mask(pin));
                    return new RuntimeProductPage(composeWithoutInventory(bases, false), null);
                }
                case INACTIVE -> {
                    log.info("commerce_enrich_unserviceable pin={} reason=INACTIVE area={}",
                            mask(pin), resolution.serviceAreaId());
                    return new RuntimeProductPage(composeWithoutInventory(bases, false),
                            new RuntimeServiceArea(resolution.serviceAreaId(), false));
                }
                case NO_ACTIVE_ROUTE -> {
                    // configuration-quality signal, never disguised as plain unserviceable
                    log.warn("commerce_enrich_unserviceable pin={} reason=NO_ACTIVE_ROUTE area={}",
                            mask(pin), resolution.serviceAreaId());
                    return new RuntimeProductPage(composeWithoutInventory(bases, false),
                            new RuntimeServiceArea(resolution.serviceAreaId(), false));
                }
                default -> throw new IllegalStateException("unknown resolution " + resolution.status());
            }
        } catch (RuntimeException e) {
            // Infrastructure failures propagate typed — never masked as business state. The
            // structured field is the SAFE exception class only; the throwable itself goes to
            // the logger for operator diagnostics rather than interpolating an arbitrary
            // message (which could carry internal identifiers) into the structured line.
            log.warn("commerce_enrich_failure type={}", e.getClass().getSimpleName(), e);
            throw e;
        }
    }

    // --- composition paths ----------------------------------------------------------

    /** Anonymous browse (serviceable=null) and non-serviceable PINs (serviceable=false). */
    private List<RuntimeProductCard> composeWithoutInventory(
            List<ProductCardBaseProjection> bases, Boolean serviceable) {
        boolean unconfiguredWarned = false;
        List<RuntimeProductCard> cards = new ArrayList<>(bases.size());
        for (ProductCardBaseProjection base : bases) {
            String thumb = resolveThumbnail(base, !unconfiguredWarned);
            unconfiguredWarned = unconfiguredWarned || (base.primaryAssetKey() != null && thumb == null
                    && !mediaUrls.isConfigured());
            cards.add(card(base, thumb, StockState.UNKNOWN, null, 0, serviceable, false));
        }
        return cards;
    }

    /** SERVICEABLE: ONE batched inventory read at the resolved internal location. */
    private List<RuntimeProductCard> composeServiceable(
            List<ProductCardBaseProjection> bases, String fulfillmentLocationId) {
        if (bases.isEmpty()) {
            return List.of(); // zero cards -> zero inventory work (STEP 9)
        }
        LinkedHashSet<String> skuIds = new LinkedHashSet<>();
        for (ProductCardBaseProjection base : bases) {
            skuIds.add(base.skuId());
        }
        Map<String, InventoryLookup> stock = Objects.requireNonNull(
                inventory.findInventoryBatch(skuIds, fulfillmentLocationId),
                "inventory batch adapter returned null map");

        boolean unconfiguredWarned = false;
        List<RuntimeProductCard> cards = new ArrayList<>(bases.size());
        for (ProductCardBaseProjection base : bases) {
            String thumb = resolveThumbnail(base, !unconfiguredWarned);
            unconfiguredWarned = unconfiguredWarned || (base.primaryAssetKey() != null && thumb == null
                    && !mediaUrls.isConfigured());

            // Batch-response defence (STEP 8): an OMITTED key is the port's documented MISSING;
            // a key mapped to NULL is an adapter BUG and fails fast rather than becoming a
            // plausible business state. Extra unrelated keys are ignored by construction.
            InventoryLookup lookup = stock.get(base.skuId());
            if (lookup == null) {
                if (stock.containsKey(base.skuId())) {
                    throw new IllegalStateException(
                            "inventory batch adapter mapped sku to null: " + base.skuId());
                }
                lookup = InventoryLookup.missing();
            }
            cards.add(switch (lookup.status()) {
                case PRESENT -> presentCard(base, thumb, lookup);
                case MISSING -> {
                    // data-quality signal: UNKNOWN, never a fake OUT_OF_STOCK claim (STEP 9)
                    log.warn("commerce_inventory_missing sku={}", base.skuId());
                    yield card(base, thumb, StockState.UNKNOWN, null, 0, true, false);
                }
                case INACTIVE -> {
                    log.info("commerce_inventory_inactive sku={}", base.skuId());
                    yield card(base, thumb, StockState.UNKNOWN, null, 0, true, false);
                }
            });
        }
        return cards;
    }

    private RuntimeProductCard presentCard(ProductCardBaseProjection base, String thumb,
                                           InventoryLookup lookup) {
        var record = lookup.record();
        StockState state = record.stockState();
        // Deliberate safe conversion (STEP 28): service invariants cap quantities at 1e6, but a
        // silent long->int cast is still forbidden — pathological values fail loudly.
        int maxOrder = Math.toIntExact(record.effectivePurchasableQuantity());
        Integer lowRemaining = state == StockState.LOW_STOCK
                ? Math.toIntExact(record.available())
                : null;
        boolean buyable = base.priceStatus() == PriceStatus.ACTIVE
                && base.sellingPricePaise() != null
                && state != StockState.OUT_OF_STOCK
                && maxOrder >= MINIMUM_ORDER_QUANTITY;
        return card(base, thumb, state, lowRemaining, maxOrder, true, buyable);
    }

    private RuntimeProductCard card(ProductCardBaseProjection base, String thumb, StockState state,
                                    Integer lowRemaining, int maxOrder, Boolean serviceable,
                                    boolean buyable) {
        long[] discount = discount(base.sellingPricePaise(), base.mrpPaise());
        return new RuntimeProductCard(
                base.skuId(), base.productId(), base.title(), base.brandCode(), base.verticalId(),
                thumb,
                base.sellingPricePaise(), base.mrpPaise(),
                discount == null ? null : discount[0],
                discount == null || discount[1] < 0 ? null : (int) discount[1],
                state, lowRemaining, maxOrder, MINIMUM_ORDER_QUANTITY,
                serviceable,
                null, null, // ETA: no ratified source exists — nothing is fabricated (STEP 16)
                buyable);
    }

    /**
     * FROZEN DISCOUNT SEMANTICS (STEP 13): derived only when both amounts exist AND mrp>selling.
     * Percent = floor(amount*100/mrp), long arithmetic, no floating point. For selling >= 1 the
     * floor is mathematically <= 99; the ONLY >99 case is a free item (selling == 0), where the
     * percent is OMITTED rather than shown as a false "99%" — the absolute amount still renders,
     * so nothing about the price is misrepresented.
     *
     * @return null when no discount; else {amountPaise, percent} with percent = -1 for "omit".
     */
    private static long[] discount(Long sellingPaise, Long mrpPaise) {
        if (sellingPaise == null || mrpPaise == null || mrpPaise <= sellingPaise) {
            return null;
        }
        long amount = mrpPaise - sellingPaise;                 // both >=0, mrp>selling: no overflow
        long percent = Math.multiplyExact(amount, 100L) / mrpPaise; // amounts capped at 1e9 by pricing
        return new long[]{amount, percent > 99 ? -1 : percent};
    }

    private String resolveThumbnail(ProductCardBaseProjection base, boolean warnIfUnconfigured) {
        if (base.primaryAssetKey() == null) {
            return null;
        }
        if (!mediaUrls.isConfigured()) {
            // Browse degradation, NOT silence on corruption: an unconfigured public base is a
            // deployment-readiness gap (CDN-HOST-1) — the card renders without an image and the
            // gap is signalled; production readiness later REQUIRES a configured base. Malformed
            // keys are a different class and still fail typed inside resolve().
            if (warnIfUnconfigured) {
                log.warn("commerce_media_unconfigured: media base URL absent; thumbnails omitted");
            }
            return null;
        }
        return mediaUrls.resolve(base.primaryAssetKey());
    }

    private static String mask(Pincode pin) {
        String v = pin.value();
        return v.substring(0, 3) + "XXX";
    }
}
