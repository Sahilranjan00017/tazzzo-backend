# TAZZZO — Performance results

Harness: `CatalogCapacityIT` (branch `feature/catalogue-capacity-harness`; `docs/ops/CAPACITY_HARNESS.md`). Environment for every run below: one developer machine (macOS, Apple Silicon), the service and the test client in one JVM, MongoDB 7 and Redis in Testcontainers on the same host, one request at a time, no images/benefits/reservations exercised. Relative numbers, not an SLA.

## Targets (proposed; ratify before the first benchmark)

| Metric | 5,000 SKUs | 25,000 | 100,000 | 1,000,000 (where feasible) |
|---|---|---|---|---|
| Product list p95 | < 200 ms | < 250 ms | < 300 ms | < 400 ms |
| PDP p95 | < 200 ms | < 250 ms | < 300 ms | < 400 ms |
| Search p95 | < 300 ms | < 400 ms | < 500 ms | documented |
| CMS admin list p95 | < 300 ms | < 400 ms | < 500 ms | documented |
| Import throughput (sustained) | ≥ 200 rows/s | ≥ 200 rows/s | ≥ 150 rows/s | multi-job |
| Import peak heap growth | < 256 MB | < 256 MB | < 512 MB | < 512 MB |
| Projection drift pass | < 1 h | < 4 h | < 24 h | documented |
| Error rate under load | < 0.1 % | < 0.1 % | < 0.1 % | < 0.1 % |

Workload assumptions to record with each run: concurrent users, dataset distribution (verticals, brands, attribute cardinality), container size, MongoDB tier, index sizes, cost per hour.

## Results

### 2026-10-07 — 5,000 SKUs (branch `feature/catalogue-capacity-harness` on `main` `7d491dd`)
Seed: 4,968 consumer-visible cards (32 SKUs fell in 2 of 295 seed verticals outside the release scope — by design). Seed time: products 0.2 s, price+stock 18.4 s (two CAS transactions per SKU = 3.7 ms/SKU), projection 11.2 s.

| Metric | p50 | p95 | max | Target (5k) | Pass |
|---|---|---|---|---|---|
| List first page (20) | 9.1 ms | 15.0 ms | 35.8 ms | < 200 ms | ✅ |
| List 3rd page via cursor | 7.3 ms | 10.7 ms | 15.6 ms | — | ✅ |
| Product detail | 6.4 ms | 8.0 ms | 11.4 ms | < 200 ms | ✅ |
| Search, common token | 17.7 ms | 21.8 ms | 54.5 ms | < 300 ms | ✅ |
| Search, two tokens | 13.8 ms | 16.5 ms | 30.0 ms | < 300 ms | ✅ |
| Search, rare brand token | 16.6 ms | 20.4 ms | 46.0 ms | < 300 ms | ✅ |
| Admin list (vertical, 50) | 1.0 ms | 1.3 ms | 1.7 ms | < 300 ms | ✅ |
| Admin list (unfiltered, 200) | 2.5 ms | 3.7 ms | 13.7 ms | < 300 ms | ✅ |
| Projection rebuild per SKU | 2.0 ms | 3.4 ms | 48 ms | drift pass < 1 h | ✅ (0.2 min sequential) |
| Product import (real path, 500-row files) | 3.6 s / 500 rows | — | 4.3 s | ≥ 200 rows/s | ❌ **127 rows/s** |

Sizes: products 2.2 MB data / 0.5 MB index; product_card_base 2.4 / 1.2; price_current 1.1 / 0.5; inventory 1.2 / 0.4. Plans: list `IXSCAN classification…`, search `IXSCAN card_search_tokens` (no collection scan). Heap growth in the test JVM after seeding: 2 MB (end 52 MB).

Bottleneck: the synchronous import path (one transaction per row through `MintService.mint`, plus whole-file validation) — the async import engine (Phase 8) must batch validation and apply rows in bounded parallel batches to reach ≥ 200 rows/s. Everything else has an order of magnitude of headroom at this size.

_(25,000 and 100,000 runs: in progress; appended when complete)_

Synthetic datasets are TEST DATA only and must never be published to a customer-visible release.
