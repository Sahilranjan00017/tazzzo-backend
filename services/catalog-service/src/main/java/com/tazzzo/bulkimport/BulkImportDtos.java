package com.tazzzo.bulkimport;

import java.util.List;

/** Wire shapes of the bulk price/stock import. Boxed fields so a missing value is reported, never defaulted to 0. */
final class BulkImportDtos {

    private BulkImportDtos() { }

    record PriceRow(String skuId, Long sellingPricePaise, Long mrpPaise, String currency, Long expectedVersion) { }

    record StockRow(String skuId, String locationId, Long onHand, Long lowStockThreshold, Long maxPurchasable,
                    Long expectedVersion) { }

    record PriceImportRequest(Boolean dryRun, List<PriceRow> rows) { }

    record StockImportRequest(Boolean dryRun, List<StockRow> rows) { }

    /** {@code row} is the 0-based index in the submitted array. */
    record RowError(int row, String code, String message) { }

    /**
     * Outcome per row: VALID (dry run), APPLIED, FAILED (code + message), or NOT_ATTEMPTED (the run stopped early because
     * the datastore became unavailable; re-submitting the file is safe because every row is a set, not an increment).
     */
    record RowResult(int row, String key, String outcome, Long version, String code, String message) { }

    record ImportReport(String importId, String kind, boolean dryRun, int rows, int applied, int failed, int notAttempted,
                        List<RowResult> results, int unchanged) {
        ImportReport(String importId, String kind, boolean dryRun, int rows, int applied, int failed, int notAttempted,
                     List<RowResult> results) {
            this(importId, kind, dryRun, rows, applied, failed, notAttempted, results, 0);
        }
    }

    /** Rows shaped exactly like the single {@code POST /api/v1/products} request. */
    record ProductImportRequest(Boolean dryRun, List<com.tazzzo.catalog.api.ApiDtos.CreateProductRequest> rows) { }
}
