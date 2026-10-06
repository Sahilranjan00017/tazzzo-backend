# TAZZZO — Performance results

**No capacity or load test has been run yet.** No load-test harness exists in any repository (verified 2026-10-07). The only timing on record: a 500-row product import applies synchronously in about 21 s (`docs/ops/BULK_IMPORT.md`).

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
_(none yet — each run appends: date, SHA, dataset size/generator, environment, metrics, bottlenecks, resource usage, cost, recommendations)_

Synthetic datasets are TEST DATA only and must never be published to a customer-visible release.
