package com.tazzzo.bulkimport;

import com.tazzzo.catalog.api.AdminActors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/imports/prices}, {@code /inventory} and {@code /products}: up to 500 rows, validated as a whole before anything
 * is written; {@code "dryRun": true} validates and reports without writing. Same authorisation as the single-row admin
 * writes (the admin access policy governs {@code /api/v1/admin/**}); every applied row is attributed to the caller.
 */
@RestController
@RequestMapping("/api/v1/admin/imports")
class BulkImportController {

    private final BulkImportService imports;

    BulkImportController(BulkImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/prices")
    BulkImportDtos.ImportReport prices(@RequestBody BulkImportDtos.PriceImportRequest body, HttpServletRequest request) {
        return imports.importPrices(body, AdminActors.require(request));
    }

    @PostMapping("/products")
    BulkImportDtos.ImportReport products(@RequestBody BulkImportDtos.ProductImportRequest body, HttpServletRequest request) {
        return imports.importProducts(body, AdminActors.require(request));
    }

    @PostMapping("/inventory")
    BulkImportDtos.ImportReport inventory(@RequestBody BulkImportDtos.StockImportRequest body, HttpServletRequest request) {
        return imports.importStock(body, AdminActors.require(request));
    }
}
