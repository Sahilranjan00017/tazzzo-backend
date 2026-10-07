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

### 2026-10-07 — 25,000 SKUs (same branch, same machine)
Seed: 24,832 cards; products 0.9 s, price+stock 101 s (4.1 ms/SKU), projection 125 s (rebuild p50 4.1 ms, p95 11.1 ms, max 493 ms); drift pass 1.7 min sequential.

| Metric | p50 | p95 | max | Target (25k) | Pass |
|---|---|---|---|---|---|
| List first page | 10.1 ms | 15.6 ms | 41 ms | < 250 ms | ✅ |
| List 3rd page | 8.6 ms | 11.3 ms | 20 ms | — | ✅ |
| Product detail | 7.7 ms | 11.0 ms | 20 ms | < 250 ms | ✅ |
| Search common / two tokens / rare | 21.0 / 17.1 / 19.9 ms | 28.2 / 22.5 / 25.9 ms | 51 ms | < 400 ms | ✅ |
| Admin list (vertical 50 / unfiltered 200) | 1.4 / 2.9 ms | 2.6 / 5.1 ms | 13 ms | < 400 ms | ✅ |
| Import (500-row files ×4) | 4.0 s / 500 | — | 5.0 s | ≥ 200 rows/s | ❌ **116 rows/s** |

Sizes: products 9.9 MB / 2.6 MB index; product_card_base 12.0 / 8.4. Plans unchanged (IXSCAN). Heap growth 7 MB.

### 2026-10-07 — 100,000 SKUs (same branch, same machine; 30 min wall clock)
Seed: 99,324 cards; products 4.0 s (insertMany), **price+stock 888 s (8.9 ms/SKU: two CAS transactions per SKU)**, projection 789 s (rebuild p50 6.7 ms, p95 14.0 ms, max 600 ms); drift pass 11.2 min if sequential and unthrottled.

| Metric | p50 | p95 | max | Target (100k) | Pass |
|---|---|---|---|---|---|
| List first page | 31.4 ms | 65.2 ms | 123 ms | < 300 ms | ✅ |
| List 3rd page | 23.9 ms | 52.8 ms | 137 ms | — | ✅ |
| Product detail | 21.5 ms | 39.1 ms | 72 ms | < 300 ms | ✅ |
| Search common token | 51.7 ms | 93.2 ms | 816 ms | < 500 ms | ✅ (max outlier) |
| Search two tokens | 39.9 ms | 69.9 ms | 192 ms | < 500 ms | ✅ |
| Search rare brand token | 55.8 ms | 146.4 ms | 373 ms | < 500 ms | ✅ |
| Admin list (vertical 50 / unfiltered 200) | 4.9 / 7.4 ms | 8.7 / 13.3 ms | 46 ms | < 500 ms | ✅ |
| Admin get | 8.0 ms | 26.9 ms | 57 ms | — | ✅ |
| Import (500-row files ×4) | 11.6 s / 500 | — | 15.1 s | ≥ 150 rows/s | ❌ **39 rows/s** |

Sizes: products 37.3 MB data / 9.1 MB index; product_card_base 47.8 / 27.9 (the multikey `card_search_tokens` index dominates). Plans: still IXSCAN for list and search. Heap growth 32 MB (end 81 MB).

### Findings from the three runs
1. **Import throughput degrades with catalogue size** (127 → 116 → 39 rows/s): whole-file validation plus one `MintService.mint` transaction per row, with identity/GTIN uniqueness checks that grow with the collection. The Phase 8 async engine must batch validation lookups and apply rows in bounded parallel batches; target ≥ 200 rows/s at 100k needs a ~5× improvement.
2. **Projection drift pass is a configuration limit, not a compute limit**: compute is ~6.7 ms/SKU (11 min for 100k), but `card-reconcile-limit=500` every `card-reconcile-ms=300000` caps a full pass at 100,000/500 × 5 min ≈ **16.7 h at 100k** (≈ 7 days at 1M). Raise the limit/frequency proportionally to catalogue size (Phase 8/9).
3. **Search tail**: rare-token p95 146 ms and a common-token max of 816 ms at 100k on the prefix-regex `$all` plan over a 28 MB multikey index; fine for launch, but ranking/facets (Phase 9) should move to a real text/search index before 1M.
4. **Read paths have ample headroom** at 100k on one laptop; the 1M target is extrapolated, not measured (seed alone would take ~5 h with the current CAS write paths), and is documented as a risk until a staging-sized run exists.
5. Non-2xx samples (2–3 of 200 on PDP) are random picks of SKUs in verticals outside the release scope (404 by design), not errors.

Raw JSON: `target/capacity/capacity-{5000,25000,100000}.json` from the harness runs (not committed).

Synthetic datasets are TEST DATA only and must never be published to a customer-visible release.
