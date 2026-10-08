package com.tazzzo.inventory.admin;

import com.tazzzo.inventory.InvalidInventoryException;
import com.tazzzo.inventory.InventoryRecord;
import com.tazzzo.inventory.InventoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INTERNAL admin list of stock rows: {@code GET /api/v1/admin/inventory?location=&state=&limit=&cursor=}. Rows come in
 * {@code (skuId, fulfillmentLocationId)} order with an opaque keyset {@code cursor} (the position of the last row read);
 * {@code location} narrows to one fulfilment location and {@code state} to a server-derived stock state ({@code IN_STOCK} |
 * {@code LOW_STOCK} | {@code OUT_OF_STOCK} over active rows, or {@code INACTIVE}), so the CMS can show a stock list and a
 * low-stock feed without a location registry. Same authorisation as every admin read. The query grammar is closed: only
 * those four names, each at most once and never empty — a typo is refused, never ignored, so it cannot widen the list.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryAdminListController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = InventoryService.LIST_MAX_LIMIT;
    static final Set<String> PARAMS = Set.of("location", "state", "limit", "cursor");
    static final Set<String> STATES = Set.of("IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK", "INACTIVE");
    /**
     * The list only positions on ids of at most 128 code points: two of them at 4 UTF-8 bytes each plus the length prefix
     * (up to 256 UTF-16 units) is 1,028 bytes, 1,371 base64url characters — every cursor the list issues fits.
     */
    static final int MAX_CURSOR = 1_400;

    record StockListRow(String skuId, String fulfillmentLocationId, long onHand, long reserved, long available,
                        long lowStockThreshold, long maxPurchasable, long version, boolean active, String stockState) { }

    record StockListPage(List<StockListRow> items, String nextCursor) { }

    private final InventoryService inventory;

    public InventoryAdminListController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public StockListPage listStock(HttpServletRequest request) {
        String location = null, state = null, cursor = null;
        int limit = DEFAULT_LIMIT;
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            String name = e.getKey();
            if (!PARAMS.contains(name)) throw new InvalidInventoryException("unsupported query parameter");
            if (e.getValue().length != 1 || e.getValue()[0].isEmpty()) {
                throw new InvalidInventoryException("query parameter " + name + " must appear exactly once and not be empty");
            }
            String v = e.getValue()[0];
            switch (name) {
                case "location" -> location = v;
                case "state" -> {
                    if (!STATES.contains(v)) throw new InvalidInventoryException("state must be one of IN_STOCK, LOW_STOCK, OUT_OF_STOCK, INACTIVE");
                    state = v;
                }
                case "limit" -> {
                    if (!v.matches("[1-9][0-9]{0,2}") || Integer.parseInt(v) > MAX_LIMIT) {
                        throw new InvalidInventoryException("limit must be between 1 and " + MAX_LIMIT);
                    }
                    limit = Integer.parseInt(v);
                }
                default -> cursor = v;
            }
        }
        String[] after = cursor == null ? null : decode(cursor);
        InventoryService.ListPage page = inventory.list(location, state, after == null ? null : after[0], after == null ? null : after[1], limit);
        return new StockListPage(page.rows().stream().map(InventoryAdminListController::view).toList(),
                page.nextSku() == null ? null : encode(page.nextSku(), page.nextLocation()));
    }

    static StockListRow view(InventoryRecord r) {
        return new StockListRow(r.skuId(), r.fulfillmentLocationId(), r.onHand(), r.reserved(), r.available(),
                r.lowStockThreshold(), r.maxPurchasable(), r.version(), r.active(),
                r.active() ? r.stockState().name() : "INACTIVE");
    }

    /** The position as base64url of {@code <length of sku>:<sku><location>}: unambiguous for any characters in either id. */
    static String encode(String sku, String location) {
        String raw = sku.length() + ":" + sku + location;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String[] decode(String cursor) {
        try {
            if (cursor.length() > MAX_CURSOR) throw new IllegalArgumentException();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            int n = Integer.parseInt(raw.substring(0, colon));
            String sku = raw.substring(colon + 1, colon + 1 + n);
            String location = raw.substring(colon + 1 + n);
            if (sku.isEmpty() || location.isEmpty()) throw new IllegalArgumentException();
            return new String[]{sku, location};
        } catch (RuntimeException e) {
            throw new InvalidInventoryException("invalid cursor");
        }
    }
}
