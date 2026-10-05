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
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.pricing.PriceReadPort;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Public product search (backend completion PR-G). Candidates come from the {@code product_card_base} projection
 * through the multikey index on {@code search_tokens}: every query token must PREFIX-match a stored token
 * (anchored regexes inside {@code $all}, which the index serves). Each candidate is then re-checked against the
 * catalogue truth ({@code products} with {@link ConsumerEligibility#within} over the release's reachable verticals),
 * so a stale projection row can hide a product but never show an ineligible one. Pages are keyset-paged in ascending
 * sku order through the SAME signed cursor codec as the category list; the normalised query is bound into the cursor
 * as a non-reversible fingerprint. Ranking is deliberately absent (deterministic id order): relevance scoring is a
 * product decision recorded in the status document.
 */
public class CommerceSearchService {

    private static final Logger log = LoggerFactory.getLogger(CommerceSearchService.class);
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    /** The cursor's node field for a search page: a keyed fingerprint of the normalised query, never the query. */
    static final String CURSOR_NODE_PREFIX = "search:";
    private static final List<String> PAGE_FIELDS =
            List.of("_id", "title", "brand_code", "classification.vertical_id", "version");

    private final SnapshotTaxonomyReader snapshots;
    private final ConsumerTaxonomyScopeResolver scopes;
    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerCursorCodec cursors;
    private final CurrentCardBaseComposer baseComposer;
    private final ProductCardRuntimeEnricher enricher;
    private final MongoDatabase db;
    private final boolean freshnessReady;

    public CommerceSearchService(SnapshotTaxonomyReader snapshots, ConsumerTaxonomyScopeResolver scopes,
                                 ConsumerReleaseResolver releases, ConsumerAdmissionGate gate,
                                 ConsumerCursorCodec cursors, ProductCardBaseReader baseReader, PriceReadPort prices,
                                 ProductCardRuntimeEnricher enricher, MongoDatabase db, boolean freshnessReady) {
        this.snapshots = snapshots;
        this.scopes = scopes;
        this.releases = releases;
        this.gate = gate;
        this.cursors = cursors;
        this.baseComposer = new CurrentCardBaseComposer(baseReader, prices);
        this.enricher = enricher;
        this.db = db;
        this.freshnessReady = freshnessReady;
    }

    public CommerceProductPage search(String rawQuery, String explicitRelease, String pageSizeParam, String cursorParam,
                                      LocationQuery location, ConsumerIdentity identity) {
        if (!freshnessReady) {
            throw new CommerceReadUnavailableException(CommerceReadUnavailableException.Category.FRESHNESS_NOT_READY,
                    "projection freshness not enabled");
        }
        cursors.requireReady();
        List<String> tokens;
        try {
            tokens = SearchTokens.query(rawQuery);
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.InvalidRequest("invalid query");
        }
        if (tokens.isEmpty()) {
            throw new ConsumerFailures.InvalidRequest("q is required");
        }
        String node = CURSOR_NODE_PREFIX + cursors.fingerprint("q:" + String.join(" ", tokens));
        String locationContext = locationContext(location);

        ConsumerCursorCodec.ListCursor cursor = null;
        int pageSize;
        String release;
        if (cursorParam != null) {
            cursor = cursors.decode(cursorParam);
            if (!cursor.nodeId().equals(node)) {
                throw new ConsumerFailures.InvalidCursor("cursor query");
            }
            if (!cursor.locationContext().equals(locationContext)) {
                throw new ConsumerFailures.InvalidCursor("cursor location");
            }
            if (explicitRelease != null && !explicitRelease.isBlank() && !explicitRelease.trim().equals(cursor.releaseId())) {
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
        gate.charge(ConsumerObservability.Route.COMMERCE_SEARCH, identity, 1L + pageSize);

        List<String> scope = releaseScope(release);
        if (scope.isEmpty()) {
            return new CommerceProductPage(release, new RuntimeProductPage(List.of(), null), null);
        }

        // 1) candidates from the projection: every token is an anchored prefix (index-served), ascending sku order
        List<Bson> predicates = new ArrayList<>();
        for (String t : tokens) {
            predicates.add(Filters.regex("search_tokens", Pattern.compile("^" + Pattern.quote(t))));
        }
        List<Bson> filter = new ArrayList<>();
        filter.add(Filters.and(predicates));
        filter.add(Filters.in("vertical_id", scope));
        if (cursor != null) {
            filter.add(Filters.gt("sku_id", cursor.lastProductId()));
        }
        List<String> candidates = new ArrayList<>();
        for (Document d : db.getCollection(ProductCardProjectionService.COLLECTION).find(Filters.and(filter))
                .projection(Projections.include("sku_id")).sort(Sorts.ascending("sku_id")).limit(pageSize + 1)) {
            candidates.add(d.getString("sku_id"));
        }
        boolean more = candidates.size() > pageSize;
        List<String> pageCandidates = more ? candidates.subList(0, pageSize) : candidates;

        // 2) the catalogue decides eligibility: a projection row never admits what the source refuses
        List<Document> eligible = pageCandidates.isEmpty() ? List.of()
                : db.getCollection("products")
                        .find(Filters.and(ConsumerEligibility.within(scope), Filters.in("_id", pageCandidates)))
                        .projection(Projections.include(PAGE_FIELDS)).sort(Sorts.ascending("_id")).into(new ArrayList<>());
        if (eligible.size() < pageCandidates.size()) {
            log.info("commerce_search_candidates_dropped requested={} eligible={}", pageCandidates.size(), eligible.size());
        }

        List<String> skuIds = eligible.stream().map(d -> d.getString("_id")).toList();
        java.util.Map<String, Document> itemBySku = new java.util.LinkedHashMap<>();
        eligible.forEach(d -> itemBySku.put(d.getString("_id"), d));
        List<ProductCardBaseProjection> bases;
        try {
            bases = baseComposer.compose(skuIds, sku -> gapFacts(itemBySku.get(sku)),
                    sku -> log.warn("commerce_search_base_missing sku={}", sku));
        } catch (CurrentCardBaseComposer.FactsOutOfBoundsException e) {
            throw new ConsumerFailures.Unavailable(e.getMessage());
        }
        RuntimeProductPage runtimePage = DomainReadGuard.guard(() -> enricher.enrichPage(bases, location));

        String next = null;
        if (more) {
            // the continuation is the last CANDIDATE, so a dropped candidate can never make a page loop or repeat
            String last = pageCandidates.get(pageCandidates.size() - 1);
            next = cursors.encode(new ConsumerCursorCodec.ListCursor(node, release, pageSize, last, locationContext));
        }
        return new CommerceProductPage(release, runtimePage, next);
    }

    /** Every consumer-reachable vertical of the release: the union of the subtrees of its super categories. */
    private List<String> releaseScope(String release) {
        Set<String> all = new LinkedHashSet<>();
        for (Document root : snapshots.nodesOfType(release, "super_category")) {
            String id = root.getString("node_id");
            if (id != null && scopes.isReachable(release, root)) {
                all.addAll(scopes.scope(release, id));
            }
        }
        return new ArrayList<>(all);
    }

    private String locationContext(LocationQuery location) {
        return location.pincode()
                .map(pin -> cursors.fingerprint("pin:" + pin.value()))
                .orElse(ConsumerCursorCodec.LOCATION_ANONYMOUS);
    }

    private static CatalogCardFacts gapFacts(Document item) {
        Document classification = item.get("classification", Document.class);
        String sku = item.getString("_id");
        try {
            return new CatalogCardFacts(sku, sku, item.getString("title"), item.getString("brand_code"),
                    classification == null ? null : classification.getString("vertical_id"),
                    item.getInteger("version", 0));
        } catch (IllegalArgumentException e) {
            throw new CurrentCardBaseComposer.FactsOutOfBoundsException(sku, e);
        }
    }

    private static int validatedPageSize(String raw) {
        int n = parsePageSize(raw);
        if (n < 1 || n > MAX_PAGE_SIZE) {
            throw new ConsumerFailures.InvalidRequest("page_size out of range");
        }
        return n;
    }

    private static int parsePageSize(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]{0,2}")) {
            throw new ConsumerFailures.InvalidRequest("page_size must be a positive integer");
        }
        return Integer.parseInt(raw);
    }
}
