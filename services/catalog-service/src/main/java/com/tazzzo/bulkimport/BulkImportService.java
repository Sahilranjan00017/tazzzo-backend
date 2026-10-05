package com.tazzzo.bulkimport;

import com.tazzzo.catalog.tx.ProductNotFoundException;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.inventory.InventoryException;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PricingException;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Bulk price and stock import for catalogue operations. Two phases:
 * <ol>
 *   <li><b>Validate the whole file</b> (shape, the write path's own command validation, duplicate keys, unknown products).
 *   Any error rejects the file and NOTHING is written: a half-valid file is never half-applied.</li>
 *   <li><b>Apply row by row</b> through the same attributed, CAS-guarded, audited service call the single-row admin API
 *   uses, so an import can do nothing a person could not do one row at a time. Rows are independent: a stale
 *   {@code expectedVersion} fails that row only. A datastore failure stops the run; the remaining rows are reported
 *   NOT_ATTEMPTED (every row is a set, so re-submitting the file is safe).</li>
 * </ol>
 * One summary {@code domain_events} row per applied run records who imported what and with what result.
 */
public class BulkImportService {

    private static final Logger log = LoggerFactory.getLogger(BulkImportService.class);
    static final int MAX_ROWS = 500;
    static final String SOURCE = "admin-bulk-import";

    private final PricingService pricing;
    private final InventoryService inventory;
    private final ProductQueryService products;
    private final DomainAudit audit;
    private final Tx tx;

    public BulkImportService(PricingService pricing, InventoryService inventory, ProductQueryService products, DomainAudit audit, Tx tx) {
        this.pricing = pricing;
        this.inventory = inventory;
        this.products = products;
        this.audit = audit;
        this.tx = tx;
    }

    BulkImportDtos.ImportReport importPrices(BulkImportDtos.PriceImportRequest req, Actor actor) {
        List<BulkImportDtos.PriceRow> rows = rowsOf(req == null ? null : req.rows());
        List<UpsertPriceCommand> commands = new ArrayList<>();
        List<BulkImportDtos.RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            BulkImportDtos.PriceRow r = rows.get(i);
            UpsertPriceCommand cmd = null;
            try {
                if (r == null || r.sellingPricePaise() == null || r.mrpPaise() == null) {
                    throw new IllegalArgumentException("skuId, sellingPricePaise and mrpPaise are required");
                }
                Currency currency;
                try {
                    currency = r.currency() == null ? Currency.INR : Currency.valueOf(r.currency());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unsupported currency");
                }
                cmd = new UpsertPriceCommand(r.skuId(), r.sellingPricePaise(), r.mrpPaise(), currency, null, null, SOURCE,
                        r.expectedVersion());
                PricingService.validateCommand(cmd);
            } catch (PricingException | IllegalArgumentException e) {
                errors.add(new BulkImportDtos.RowError(i, "INVALID_ROW", e.getMessage()));
                continue;
            }
            if (!seen.add(cmd.skuId())) {
                errors.add(new BulkImportDtos.RowError(i, "DUPLICATE_ROW", "skuId appears more than once in this file"));
                continue;
            }
            if (!productExists(cmd.skuId())) {
                errors.add(new BulkImportDtos.RowError(i, "UNKNOWN_PRODUCT", "no such product"));
                continue;
            }
            commands.add(cmd);
        }
        rejectIfAny(errors);
        boolean dryRun = Boolean.TRUE.equals(req.dryRun());
        return run("prices", dryRun, commands, UpsertPriceCommand::skuId, cmd -> pricing.upsertPrice(cmd, actor), actor);
    }

    BulkImportDtos.ImportReport importStock(BulkImportDtos.StockImportRequest req, Actor actor) {
        List<BulkImportDtos.StockRow> rows = rowsOf(req == null ? null : req.rows());
        List<SetInventoryCommand> commands = new ArrayList<>();
        List<BulkImportDtos.RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            BulkImportDtos.StockRow r = rows.get(i);
            SetInventoryCommand cmd;
            try {
                if (r == null || r.onHand() == null || r.lowStockThreshold() == null || r.maxPurchasable() == null) {
                    throw new IllegalArgumentException("skuId, locationId, onHand, lowStockThreshold and maxPurchasable are required");
                }
                cmd = new SetInventoryCommand(r.skuId(), r.locationId(), r.onHand(), r.lowStockThreshold(), r.maxPurchasable(),
                        SOURCE, r.expectedVersion());
                InventoryService.validateCommand(cmd);
            } catch (InventoryException | IllegalArgumentException e) {
                errors.add(new BulkImportDtos.RowError(i, "INVALID_ROW", e.getMessage()));
                continue;
            }
            if (!seen.add(cmd.skuId() + "|" + cmd.fulfillmentLocationId())) {
                errors.add(new BulkImportDtos.RowError(i, "DUPLICATE_ROW", "skuId/locationId appears more than once in this file"));
                continue;
            }
            if (!productExists(cmd.skuId())) {
                errors.add(new BulkImportDtos.RowError(i, "UNKNOWN_PRODUCT", "no such product"));
                continue;
            }
            commands.add(cmd);
        }
        rejectIfAny(errors);
        boolean dryRun = Boolean.TRUE.equals(req.dryRun());
        return run("inventory", dryRun, commands, c -> c.skuId() + "|" + c.fulfillmentLocationId(),
                cmd -> inventory.setInventory(cmd, actor), actor);
    }

    private static <T> List<T> rowsOf(List<T> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new ImportRejectedException("rows must contain 1.." + MAX_ROWS + " entries", List.of());
        }
        if (rows.size() > MAX_ROWS) {
            throw new ImportRejectedException("rows must contain 1.." + MAX_ROWS + " entries", List.of());
        }
        return rows;
    }

    private boolean productExists(String skuId) {
        try {
            products.requireProduct(skuId);
            return true;
        } catch (ProductNotFoundException e) {
            return false;
        }
    }

    private static void rejectIfAny(List<BulkImportDtos.RowError> errors) {
        if (!errors.isEmpty()) {
            throw new ImportRejectedException(errors.size() + " row(s) are invalid; nothing was written", errors);
        }
    }

    <C> BulkImportDtos.ImportReport run(String kind, boolean dryRun, List<C> commands, Function<C, String> key,
                                                Function<C, Long> apply, Actor actor) {
        String importId = "IMP-" + new ObjectId().toHexString();
        List<BulkImportDtos.RowResult> results = new ArrayList<>();
        int applied = 0, failed = 0, notAttempted = 0;
        boolean stopped = false;
        for (int i = 0; i < commands.size(); i++) {
            C cmd = commands.get(i);
            if (dryRun) {
                results.add(new BulkImportDtos.RowResult(i, key.apply(cmd), "VALID", null, null, null));
                continue;
            }
            if (stopped) {
                notAttempted++;
                results.add(new BulkImportDtos.RowResult(i, key.apply(cmd), "NOT_ATTEMPTED", null, null, null));
                continue;
            }
            try {
                long version = apply.apply(cmd);
                applied++;
                results.add(new BulkImportDtos.RowResult(i, key.apply(cmd), "APPLIED", version, null, null));
            } catch (PricingException | InventoryException e) {
                failed++;
                results.add(new BulkImportDtos.RowResult(i, key.apply(cmd), "FAILED", null, failureCode(e), e.getMessage()));
            } catch (RuntimeException e) {
                failed++;
                stopped = true;
                log.warn("bulk_import_stopped kind={} import_id={} row={} error={}", kind, importId, i, e.getClass().getSimpleName());
                results.add(new BulkImportDtos.RowResult(i, key.apply(cmd), "FAILED", null, "UNAVAILABLE",
                        "the datastore failed; the run stopped here"));
            }
        }
        if (!dryRun) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("kind", kind);
            detail.put("rows", commands.size());
            detail.put("applied", applied);
            detail.put("failed", failed);
            detail.put("not_attempted", notAttempted);
            try {
                tx.run(s -> audit.append(s, new DomainEvent("bulk_import", importId, "BULK_IMPORT_APPLIED", detail, actor)));
            } catch (RuntimeException e) {
                // every applied row already carries its own audit row; the summary is a convenience
                log.warn("bulk_import_summary_audit_failed import_id={} error={}", importId, e.getClass().getSimpleName());
            }
        }
        log.info("bulk_import kind={} import_id={} dry_run={} rows={} applied={} failed={} not_attempted={}", kind, importId,
                dryRun, commands.size(), applied, failed, notAttempted);
        return new BulkImportDtos.ImportReport(importId, kind, dryRun, commands.size(), applied, failed, notAttempted, results);
    }

    private static String failureCode(RuntimeException e) {
        String name = e.getClass().getSimpleName();
        return name.contains("Conflict") ? "STALE_VERSION" : name.contains("NotFound") ? "NOT_FOUND" : "INVALID_ROW";
    }
}
