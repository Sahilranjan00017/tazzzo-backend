package com.tazzzo.inventory.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.inventory.InvalidInventoryException;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryNotFoundException;
import com.tazzzo.inventory.InventoryRecord;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INTERNAL admin transport for stock of one SKU at one fulfilment location. {@code PUT} is an ABSOLUTE set (never a
 * delta) of {@code onHand} / threshold / cap, create when {@code expectedVersion} is absent and compare-and-set otherwise;
 * it can never push {@code onHand} below live reservations and never touches {@code reserved} (owned by the reservation
 * flow). {@code activate}/{@code deactivate} delist/relist the row. The audit actor is the AUTHENTICATED principal; the
 * SKU must be an existing product. Rules live in {@link InventoryService}.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory/{skuId}/{locationId}")
public class InventoryAdminController {

    static final String SOURCE = "admin-api";

    record StockRequest(Long onHand, Long lowStockThreshold, Long maxPurchasable, Long expectedVersion) { }

    record VersionRequest(Long expectedVersion) { }

    record StockResponse(String skuId, String fulfillmentLocationId, long onHand, long reserved, long available,
                         long lowStockThreshold, long maxPurchasable, long version, boolean active) { }

    private final InventoryService inventory;
    private final ProductQueryService products;

    public InventoryAdminController(InventoryService inventory, ProductQueryService products) {
        this.inventory = inventory;
        this.products = products;
    }

    @GetMapping
    public StockResponse get(@PathVariable("skuId") @Schema(pattern = com.tazzzo.catalog.domain.ProductIds.REGEX) String skuId, @PathVariable("locationId") String locationId) {
        products.requireProduct(skuId);
        InventoryLookup lookup = inventory.findInventory(skuId, locationId);
        InventoryRecord r = lookup.record();
        if (r == null) {
            throw new InventoryNotFoundException("no inventory row for this sku and location");
        }
        return new StockResponse(r.skuId(), r.fulfillmentLocationId(), r.onHand(), r.reserved(), r.onHand() - r.reserved(),
                r.lowStockThreshold(), r.maxPurchasable(), r.version(), r.active());
    }

    @PutMapping
    public ResponseEntity<StockResponse> put(@PathVariable("skuId") @Schema(pattern = com.tazzzo.catalog.domain.ProductIds.REGEX) String skuId, @PathVariable("locationId") String locationId,
                                             @RequestBody StockRequest body, HttpServletRequest request) {
        if (body == null || body.onHand() == null || body.lowStockThreshold() == null || body.maxPurchasable() == null) {
            throw new InvalidInventoryException("onHand, lowStockThreshold and maxPurchasable are required");
        }
        products.requireProduct(skuId);
        inventory.setInventory(new SetInventoryCommand(skuId, locationId, body.onHand(), body.lowStockThreshold(),
                body.maxPurchasable(), SOURCE, body.expectedVersion()), AdminActors.require(request));
        return ResponseEntity.status(body.expectedVersion() == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(get(skuId, locationId));
    }

    @PostMapping("/activate")
    public StockResponse activate(@PathVariable("skuId") @Schema(pattern = com.tazzzo.catalog.domain.ProductIds.REGEX) String skuId, @PathVariable("locationId") String locationId,
                                  @RequestBody VersionRequest body, HttpServletRequest request) {
        return setActive(skuId, locationId, body, request, true);
    }

    @PostMapping("/deactivate")
    public StockResponse deactivate(@PathVariable("skuId") @Schema(pattern = com.tazzzo.catalog.domain.ProductIds.REGEX) String skuId, @PathVariable("locationId") String locationId,
                                    @RequestBody VersionRequest body, HttpServletRequest request) {
        return setActive(skuId, locationId, body, request, false);
    }

    private StockResponse setActive(String skuId, String locationId, VersionRequest body, HttpServletRequest request,
                                    boolean active) {
        if (body == null || body.expectedVersion() == null) {
            throw new InvalidInventoryException("expectedVersion is required");
        }
        products.requireProduct(skuId);
        inventory.setActive(AdminActors.require(request), skuId, locationId, body.expectedVersion(), active);
        return get(skuId, locationId);
    }
}
