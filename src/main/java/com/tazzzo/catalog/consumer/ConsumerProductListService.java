package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LIST-1 — the consumer product list under any consumer-visible taxonomy node, keyset-paginated
 * on {@code _id ASC} (PAG-2-SORT-1), rate-limited BEFORE product work, projected ONCE per page.
 *
 * <p>The order of operations is the contract (LIST-1 §3b.1), not an implementation detail:
 * <pre>
 *   0. the cursor key must be usable                    else 503, before anything
 *   1. decode/validate request + cursor                 400 INVALID_REQUEST / INVALID_CURSOR
 *   2. resolve ONE release                              a cursor's release wins; "current" is
 *                                                       never re-resolved on continuation
 *   3. requested node from the snapshot                 absent/non-active -> charge 1 -> 404
 *   4. scope = consumer-valid verticals under it        release-bound
 *   5. CHARGE Q5: 1 + effective_page_size               before any product read
 *   6. scope probe (PARENT)                             miss -> 404 · error -> 503
 *   7. page query: within(scope) [AND _id > last]       sort _id ASC · limit page_size + 1
 *                                                       ONLY the five projected fields
 *   8. the +1 row is continuation evidence, NEVER projected
 *   9. projectAll(page items) — ONE call
 *  10. next_cursor from the LAST RETURNED id, only if the +1 row existed
 * </pre>
 *
 * <p><b>Live, keyset-stable — not snapshot-stable (PAG-2 §2).</b> Membership is CURRENT on every
 * page: products may appear after the cursor, disappear, or (with a lower id) never be seen. No
 * {@code skip()}, no count query — a page is exactly one indexed range read.
 *
 * <p><b>The cost is an admission weight, not I/O.</b> {@code 1 + page_size} charges a category
 * scope and a vertical scope identically (recorded Q5 recalibration input, LIST-1 §1.1).
 */
@Service
public class ConsumerProductListService {

    /** LIST-PAGE-1: fixed by contract, an API decision — not deployment configuration. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    /** The only product fields projection consults (RP-1a / RESP-PROJ). Nothing internal is read. */
    static final List<String> PAGE_FIELDS =
            List.of("_id", "title", "brand_code", "classification.vertical_id", "attributes");

    private final SnapshotTaxonomyReader snapshots;
    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerVisibilityProbe probe;
    private final ConsumerProjectionService projection;
    private final ConsumerCursorCodec cursors;
    private final MongoDatabase db;

    public ConsumerProductListService(SnapshotTaxonomyReader snapshots,
                                      ConsumerReleaseResolver releases,
                                      ConsumerAdmissionGate gate,
                                      ConsumerVisibilityProbe probe,
                                      ConsumerProjectionService projection,
                                      ConsumerCursorCodec cursors,
                                      MongoDatabase db) {
        this.snapshots = snapshots;
        this.releases = releases;
        this.gate = gate;
        this.probe = probe;
        this.projection = projection;
        this.cursors = cursors;
        this.db = db;
    }

    /**
     * @param pageSizeParam the raw {@code page_size} query value, or null when omitted — parsed
     *        here, not by the framework, so a malformed value is counted as this route's own
     *        {@code invalid_request} outcome rather than dying before the measured boundary
     * @param cursorParam   the raw {@code cursor} query value, or null for a first page
     */
    public ConsumerDtos.ProductListResponse list(String nodeId, String explicitRelease,
                                                 String pageSizeParam, String cursorParam,
                                                 ConsumerIdentity identity) {
        cursors.requireReady();                                             // 0

        // 1 + 2: request shape, then ONE concrete release.
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
                // PAGE-SIZE-7a: a lowered ceiling is not grandfathered. Never clamp.
                throw new ConsumerFailures.InvalidCursor("cursor page size above maximum");
            }
            pageSize = cursor.pageSize();
            // The cursor's release wins, verified as a real active release the same way an
            // explicit ?release= is (TR-2). "Current" is NOT consulted: a pointer that moved
            // R1 -> R2 between pages leaves an R1 cursor on R1.
            release = releases.resolve(cursor.releaseId());
        } else {
            pageSize = pageSizeParam == null ? DEFAULT_PAGE_SIZE : validatedPageSize(pageSizeParam);
            release = releases.resolve(explicitRelease);
        }

        // 3: the requested node, from THIS release's snapshot.
        Document requested = snapshots.node(release, nodeId);
        if (requested == null || !"active".equals(requested.getString("status"))) {
            gate.charge(ConsumerObservability.Route.LIST, identity, 1);
            // L-5: absent, non-active and consumer-empty are one answer.
            throw new ConsumerFailures.NotFound("node not consumer-reachable: " + nodeId);
        }

        // 4 + 5: release-bound scope, then the charge — before any product read.
        List<String> scope = snapshots.consumerVerticalIdsInSubtree(release, nodeId);
        gate.charge(ConsumerObservability.Route.LIST, identity, 1L + pageSize);

        // 6: the scope's own visibility. This is what makes "visible node, nothing after this
        // cursor" (200, empty) distinguishable from "consumer-hidden node" (404).
        if (!probe.hasEligibleProduct(ConsumerObservability.Route.LIST,
                ConsumerObservability.ProbeScope.PARENT, scope)) {
            throw new ConsumerFailures.NotFound("node consumer-empty: " + nodeId);
        }

        // 7: one indexed range read of at most page_size + 1 rows, five fields.
        Bson filter = cursor == null
                ? ConsumerEligibility.within(scope)
                : Filters.and(ConsumerEligibility.within(scope), Filters.gt("_id", cursor.lastProductId()));
        List<Document> fetched = db.getCollection("products")
                .find(filter)
                .projection(Projections.include(PAGE_FIELDS))
                .sort(Sorts.ascending("_id"))
                .limit(pageSize + 1)
                .into(new ArrayList<>());

        // 8: the +1 row proves a continuation exists. It is not part of this page.
        boolean more = fetched.size() > pageSize;
        List<Document> pageItems = more ? fetched.subList(0, pageSize) : fetched;

        // 9: ONE projection call for the page. A malformed policy the page needs is a
        // configuration failure of the whole response (RP-6d) -- flat 503, never a partial page.
        List<ConsumerProductResponse> items;
        try {
            items = projection.projectAll(pageItems);
        } catch (ConsumerProjectionPolicyException e) {
            throw new ConsumerFailures.Unavailable("projection policy misconfigured");
        }

        // 10: continuation from the LAST RETURNED product, bound to this node, release, page size.
        String next = null;
        if (more) {
            String lastReturned = pageItems.get(pageItems.size() - 1).getString("_id");
            next = cursors.encode(new ConsumerCursorCodec.ListCursor(nodeId, release, pageSize, lastReturned));
        }
        return new ConsumerDtos.ProductListResponse(release, items, next);
    }

    /** LIST-PAGE-1 first-page rule: 1..50 accepted, anything else 400. Never clamped. */
    private static int validatedPageSize(String raw) {
        int value = parsePageSize(raw);
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new ConsumerFailures.InvalidRequest("page_size out of range");
        }
        return value;
    }

    /** Not an integer at all is a malformed REQUEST, whichever path it arrived on. */
    private static int parsePageSize(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConsumerFailures.InvalidRequest("page_size is not an integer");
        }
    }
}
