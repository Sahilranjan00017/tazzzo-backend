package com.tazzzo.inventory.admin;

import com.tazzzo.inventory.InvalidInventoryException;
import com.tazzzo.inventory.InventoryRecord;
import com.tazzzo.inventory.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * INTERNAL admin list of stock rows: {@code GET /api/v1/admin/inventory?location=&state=&limit=&cursor=}. Rows come in
 * {@code (skuId, fulfillmentLocationId)} order with a keyset cursor ({@code <sku>|<location>}, the last row of the page,
 * opaque to clients); {@code location} narrows to one fulfilment location and {@code state} to a server-derived stock
 * state ({@code IN_STOCK} | {@code LOW_STOCK} | {@code OUT_OF_STOCK} over active rows, or {@code INACTIVE}), so the
 * CMS can show a stock list and a low-stock feed without a location registry. Same authorisation as every admin read.
 * The row shape is the point read's ({@link InventoryAdminController.StockResponse}) plus the derived {@code stockState}.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryAdminListController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = InventoryService.LIST_MAX_LIMIT;

    record RowResponse(String skuId, String fulfillmentLocationId, long onHand, long reserved, long available,
                       long lowStockThreshold, long maxPurchasable, long version, boolean active, String stockState) { }

    record PageResponse(List<RowResponse> items, String nextCursor) { }

    private final InventoryService inventory;

    public InventoryAdminListController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public PageResponse list(@RequestParam(name = "location", required = false) String location,
                             @RequestParam(name = "state", required = false) String state,
                             @RequestParam(name = "limit", required = false) Integer limit,
                             @RequestParam(name = "cursor", required = false) String cursor) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw new InvalidInventoryException("limit must be between 1 and " + MAX_LIMIT);
        }
        String afterSku = null, afterLocation = null;
        if (cursor != null) {
            int bar = cursor.indexOf('|');
            if (bar <= 0 || bar == cursor.length() - 1) {
                throw new InvalidInventoryException("invalid cursor");
            }
            afterSku = cursor.substring(0, bar);
            afterLocation = cursor.substring(bar + 1);
        }
        // one extra row tells whether a next page exists without a count
        List<InventoryRecord> rows = inventory.list(blankToNull(location), blankToNull(state), afterSku, afterLocation, n + 1);
        boolean more = rows.size() > n;
        List<InventoryRecord> page = more ? rows.subList(0, n) : rows;
        InventoryRecord last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new PageResponse(page.stream().map(InventoryAdminListController::view).toList(),
                more ? last.skuId() + "|" + last.fulfillmentLocationId() : null);
    }

    static RowResponse view(InventoryRecord r) {
        return new RowResponse(r.skuId(), r.fulfillmentLocationId(), r.onHand(), r.reserved(), r.available(),
                r.lowStockThreshold(), r.maxPurchasable(), r.version(), r.active(),
                r.active() ? r.stockState().name() : "INACTIVE");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
