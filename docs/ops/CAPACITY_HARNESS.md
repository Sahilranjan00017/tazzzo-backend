# Catalogue capacity harness

`CatalogCapacityIT` seeds N **TEST DATA** products through the real write shapes and measures the service over real
HTTP. It never runs in the normal suite: it is enabled only by the environment variable `TAZZZO_CAPACITY_SKUS`.

```
cd services/catalog-service
TAZZZO_CAPACITY_SKUS=5000 ./mvnw test -Dtest=CatalogCapacityIT -Dsurefire.failIfNoSpecifiedTests=false
# results: printed table + target/capacity/capacity-5000.json
```

What it does, in order:
1. Drops the throwaway container database, bootstraps the schema, creates every index in `IndexCatalog`, loads the
   taxonomy seed and records release `R1`; one service area (PIN 560001) with one fulfilment location.
2. Inserts N validator-conformant products (`insertMany`, 1,000 per batch) round-robin across all seed verticals,
   ~N/20 brand codes, titles from a word pool (so search has common and rare tokens).
3. Writes a price (`PricingService.upsertPrice`) and stock (`InventoryService.setInventory`) per SKU: the real
   transactional, CAS-guarded paths, one transaction each.
4. Rebuilds the `product_card_base` row per SKU (`ProductCardProjectionService.rebuildOne`), timing each; the sequential
   drift-pass estimate is `N × p50`.
5. Times, after 20 warm-up calls, 200 samples each (p50 / p95 / max) of: list first page, list third page via cursor,
   product detail, search (common token, two tokens, rare brand token), admin list (filtered, unfiltered 200), admin get.
6. Imports new products through `POST /api/v1/admin/imports/products` in 500-row files (up to 4) and reports rows/s.
7. Records collection and index sizes (`collStats`) and the explain plan of the list and search queries, asserting no
   collection scan.

Caveats that make the numbers relative, not an SLA: service and client share one JVM on one machine; Mongo and Redis are
Testcontainers on the same host; no concurrency (one request at a time); images, inventory reservations and benefits are
not exercised. Results are recorded in the completion tracker `TAZZZO_PERFORMANCE_RESULTS.md`.
