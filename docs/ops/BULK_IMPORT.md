# Bulk catalogue import: products, prices and stock

`POST /api/v1/admin/imports/prices` and `POST /api/v1/admin/imports/inventory`. JSON, 1–500 rows, same authorisation as the single-row admin writes: a writer identity, never the read token.

```json
{ "dryRun": true,
  "rows": [ { "skuId": "TZP-…", "sellingPricePaise": 9900, "mrpPaise": 12000, "expectedVersion": 3 } ] }
```
Each stock row is `{ skuId, locationId, onHand, lowStockThreshold, maxPurchasable, expectedVersion? }`.

## Phases
1. **Whole-file validation.** Every row is checked before anything is written:
   - required fields;
   - the write path's own command validation (amounts, MRP not below selling, sanity ceilings, quantities);
   - duplicate keys in the file;
   - unknown products.

   Any error gives **422 `INVALID_IMPORT`** with `rowErrors[{row, code, message}]` for **every** bad row (codes `INVALID_ROW`, `DUPLICATE_ROW`, `UNKNOWN_PRODUCT`), and nothing is written.
2. **Dry run.** With `dryRun: true` the response is a report in which every row is `VALID`. Nothing is written and nothing is audited.
3. **Apply.** Each row goes through the same attributed, CAS-guarded, audited service call as `PUT /api/v1/admin/prices/{sku}` / `PUT /api/v1/admin/inventory/{sku}/{location}`. An import can do nothing a person could not do one row at a time.
   - **Rows are independent.** A stale `expectedVersion` (or a create racing an existing row) fails that row alone with `STALE_VERSION`.
   - **Datastore failure.** The row is reported `UNAVAILABLE` and the run **stops**; later rows are `NOT_ATTEMPTED`. Every row is a set, not an increment, so re-submitting the same file is safe.

## Response
```json
{ "importId": "IMP-…", "kind": "prices", "dryRun": false, "rows": 2, "applied": 1, "failed": 1, "notAttempted": 0,
  "results": [ { "row": 0, "key": "TZP-…", "outcome": "APPLIED", "version": 4 },
               { "row": 1, "key": "TZP-…", "outcome": "FAILED", "code": "STALE_VERSION", "message": "…" } ] }
```
For stock rows, `key` is `sku|location`.

## Audit
- **Per row:** each applied row writes the normal price/stock audit row attributed to the caller.
- **Per run:** each applied run (not a dry run) adds one `domain_events` row (`aggregate_type` `bulk_import`, the `importId`, `BULK_IMPORT_APPLIED`) carrying the counts. It is visible through the admin audit-read API.

## Products: `POST /api/v1/admin/imports/products`
Each row has **exactly** the shape of a single `POST /api/v1/products` request: `id, productType, identityType, internalKey, gtins[{value, market}], brandCode, title, verticalId, releaseId, classificationStatus, attributes, evidenceRefs`. A file holds 1–500 rows.

**Whole-file validation before any write.** Every row error is reported, and nothing is written. Most checks reuse the create path rather than restating it:
- **Shape:** required fields; `productType` must be `single`; identity `internal` needs `internalKey`, identity `gtin` needs `gtins`.
- **Classification:** the `releaseId` must exist in `catalogue_releases`. The `verticalId` must be a real taxonomy vertical, or one of the review sentinels `TZV-UNCLASSIFIED`/`TZV-SCOPE-BLOCKED`.
- **Attribute governance:** schema membership, required attributes, value types and claim evidence, via `AttributeGovernanceService.validate`.
- **GTINs:** every GTIN must pass its GS1 mod-10 check digit (GTIN-8/12/13/14).
- **Duplicates inside the file:** product id, `internalKey`, GTIN, and canonical identity (same brand, vertical and identity attributes).
- **Conflicts with existing data:** an `internalKey`, GTIN or canonical identity that already belongs to another product.
- **The `products` document contract itself:** the exact document mint would insert is probed against the collection's `$jsonSchema` validator inside a transaction that is always aborted.

**Stricter than a single create, on purpose:** the release and vertical must exist, and the GTIN check digit is enforced. A launch catalogue must load clean rather than fill review queues.

**Stable identity and safe re-submission:**
- **Same payload:** a row whose product already exists with the same create payload is `UNCHANGED`. It is not rewritten, it gets no new event, and it is not an error.
- **Different payload:** the same id with a different payload is a `CONFLICT` at validation, and nothing is written.

Re-submitting a file, including after a partial run, therefore applies only the rows that are missing.

**Apply.** Each row goes through `MintService.mint`, the same attributed path as `POST /api/v1/products`, with the product event, identity registry, canonical key and classification history in one transaction per product.
- A catalogue rejection (for example a race on an identity) fails that row only.
- A datastore failure stops the run, and the remaining rows are reported `NOT_ATTEMPTED`.
- One `bulk_import` summary row is written per applied run, including an `unchanged` count.

**Scope.** Variant packs and bundles reference other products, so create them with `POST /api/v1/products` once their components exist. In a test, a 500-row file applied in one call in about 21 s (`BulkProductImportIT`).

## Loading a 500-SKU launch catalogue end to end
**There is no 500-SKU product dataset in any repository today.**
- `tazzzo-research/Tazzzo_LAUNCH-CENSUS_Consumer_Eligibility_Census.md` says "no product corpus exists" and "no product seed, no product import, no fixture corpus".
- The only tabular product files are the two 50-title classification studies, which have no GTIN, brand, pack, price or stock.

The dataset is an input the business must supply. Once it exists:
1. **Prepare the products file.** One row per SKU in the single-create shape above, at most 500 rows per file, classified against the active release (`GET /api/v1/taxonomy/releases`).
2. **Products.** Call `POST /api/v1/admin/imports/products` with `"dryRun": true`, fix every `rowErrors` entry, then repeat with `"dryRun": false`. Re-submit the same file until every row is `APPLIED` or `UNCHANGED`.
3. **Prices.** Call `POST /api/v1/admin/imports/prices` (dry run, then apply) with `skuId, sellingPricePaise, mrpPaise`.
4. **Stock.** Call `POST /api/v1/admin/imports/inventory` (dry run, then apply) with `skuId, locationId, onHand, lowStockThreshold, maxPurchasable` for every fulfilment location that serves the launch PINs.
5. **Media.** For each SKU, upload or ingest the images and attach the media set through the media admin API (#62). Media has no bulk endpoint: 500 sets are 500 calls, and the storage/CDN provider is an external decision.
6. **Activate and publish.** Activate and publish the products through the existing product lifecycle endpoints.
7. **Verify.** The card projection worker must drain. Then `GET /v1/categories/{vertical}/products?pin=…` must list the SKUs, and the audit-read API must show one `bulk_import` summary per run.

Steps 2–4 are each two calls per 500 rows. Every step is attributed, audited and safe to repeat.

## Products at any size: asynchronous import jobs (`/api/v1/admin/imports/jobs`)
The synchronous product import above is capped at 500 rows per call and holds the request open while it applies. A launch catalogue of tens or hundreds of thousands of SKUs goes through an **import job** instead: rows are streamed in, validated and applied by a background worker in bounded batches from a cursor that survives restarts, and every row keeps its own verdict. The checks are the same `ProductImportValidator` checks and every applied row goes through the same `MintService.mint`, attributed to the admin who approved the job.

| Step | Call | Result |
|---|---|---|
| 1 | `POST /jobs` `{"kind":"products","note":"…"}` | `201`, job `OPEN` (`id` `IMPJ-…`, `version`) |
| 2 | `POST /jobs/{id}/rows` with `Content-Type: text/csv` (streamed) or `application/json` `{"rows":[…]}` — any number of times while `OPEN` | `{rowsAdded, rowsTotal, duplicates}` |
| 3 | `POST /jobs/{id}/validate` | `VALIDATING`; the worker reports `VALIDATED` (every row `VALID`/`UNCHANGED`) or `REJECTED` (some `INVALID`/`DUPLICATE`). Nothing is written. |
| 4 | fix: `GET /jobs/{id}/errors.csv`, `PUT /jobs/{id}/rows/{row}` (REJECTED → OPEN), then validate again | |
| 5 | `POST /jobs/{id}/apply` — the explicit approval | `APPLYING`; the worker reports `COMPLETED`, or `PAUSED` when the datastore failed (resume with `POST /jobs/{id}/resume`; nothing is re-applied) |
| any | `POST /jobs/{id}/cancel`; `GET /jobs`, `GET /jobs/{id}`, `GET /jobs/{id}/rows?from&limit` | |

**CSV.** The header names the columns, matched by the CMS import wizard's aliases (case/punctuation-insensitive): `id` (`productid`, `tzpid`, `sku`), `title` (`name`), `brand`, `gtin` (`barcode`, `ean`, `upc`), `market` (default `IN`), `internalKey` (`key`), `vertical`, `release`, `classification` (`status`; default `provisional`), and any `attr.<name>` column (an integer, decimal or `true`/`false` cell is carried typed, exactly as a JSON row would be). Identity is `gtin` when a GTIN is present, else `internal`. RFC 4180 quoting (a UTF-8 BOM is tolerated); a malformed file is refused as a whole (`422 INVALID_IMPORT`, naming the missing column or the kind of defect — not the line); rows ingested before the failing line stay in the job. Only one upload at a time per job (a second concurrent `rows` request is `409`). The `attr.*` columns are a server-side extension: the CMS wizard sends no attributes today. Text is read as UTF-8; other encodings are not detected.

**Rules that hold at any size.**
- **One row per product id, GTIN or internal key per job** (the internal key only for an `internal`-identity row, as the catalogue ignores a GTIN product's internal key) is enforced by two partial unique indexes (`import_rows_one_per_product`, `import_rows_one_per_identity`) at ingestion — across appends and across validation batches; a later row with the same identity is kept, already `DUPLICATE`, and makes the job `REJECTED`. Canonical identity (brand + vertical + identity attributes) is still checked within each validation batch only; a cross-batch canonical duplicate surfaces at apply as a `FAILED` row.
- **Resumable, with an exact ledger.** Validation records each batch's verdicts with the cursor and counters in ONE lease-guarded transaction (`import-jobs-batch-size`, default 500), so a worker that lost its lease writes no verdict over the new holder's. Apply renews the lease BEFORE every mint and records each row's verdict, the cursor and the counters in ONE lease-guarded transaction AFTER it: a worker that lost its lease (expired, or the job was cancelled) stops at the next row, and the one row it may have minted meanwhile is recorded by nobody — the next holder re-reads it and finds the product `UNCHANGED` (also when its own mint then reports an identity collision: the row is re-checked and recorded `UNCHANGED`, not `FAILED`). A datastore failure pauses the job in ONE lease-guarded transaction too (row `NOT_ATTEMPTED`, cursor on it, `PAUSED`); a worker that lost its lease meanwhile writes nothing. Counts and verdicts therefore never disagree: `valid`/`unchanged`/`invalid`/`duplicate` partition the rows, and a row the apply finds already present moves from `valid` to `unchanged` (an `UNCHANGED` row it minted or failed moves the other way), so a `COMPLETED` job has `applied + failed == valid`; a product is never minted twice. A `PAUSED` job resumes from the cursor; a re-run of the same file is `UNCHANGED` row by row. If a job's row ledger turns out shorter than `rowsTotal` (a row document was lost — `rowsTotal` only ever counts stored rows), the worker ends the phase with `lastError` "row ledger short" instead of waiting forever. Verdicts are never bulk-cleared: every validation pass (from `OPEN` or `REJECTED`) re-verdicts every row from row 0, so while a job is `VALIDATING` a row the cursor has not reached still shows the previous pass's verdict, and after a correction (`REJECTED` → `OPEN`) the other rows keep theirs, so the admin still sees what is left to fix; the job's `counts` likewise stay those of the last pass until the next pass starts (it zeroes them). A row marked `DUPLICATE` at ingestion stays `DUPLICATE` (it is not re-validated) even if the row it clashed with is corrected away — correct that row too (`PUT …/rows/{row}`). A correction holds the job's append lock, so it never interleaves with an upload or with the start of validation (`409` while one is in progress).
- **Explicit approval.** The apply never starts on its own: `apply` records `approvedBy`, and every product event of the run is attributed to that admin.
- **Bounded.** A request body is still bounded by `tazzzo.http.bulk-import-max-request-body-bytes` (2 MiB by default, about 20k CSV rows): a larger file is appended in several `rows` requests. A job holds at most `tazzzo.imports.max-rows-per-job` rows (250,000) and at most `tazzzo.imports.max-active-jobs` (10) jobs are open at once.
- **Concurrency.** Every admin transition is a compare-and-swap on the job's `version` (send `{"version": n}` to refuse a stale decision); one worker at a time holds a job's lease (`import-jobs-lease-ms`, 300 s — longer than the driver's ~120 s retry budget of one mint transaction — renewed per batch and before every mint; held means the token matches, so a renewal that rewrites identical values is still a hold). A worker that crashes or whose tick fails does not release its lease, so another instance resumes that job after up to 300 s and a tick stops after `import-jobs-tick-budget-ms` (30 s) so other jobs get their turn.
- **Uploads.** One at a time per job (the append lock, 15 min); validation cannot start while an upload holds it (`409`), and an upload numbers its rows from `rowsTotal` as read under the lock; every state change ends the lock, so an upload whose job left `OPEN` meanwhile (cancelled, or validated after the lock expired) is refused with `409` and its rows are removed — no phase reads past `rowsTotal`. Known limit: if the process dies mid-upload, its stored-but-unpublished rows stay behind and every later upload to that job fails on its first row; cancel that job and start a new one.
- **Cancel.** From any non-terminal state; a running worker notices at its next lease renewal — before its next mint — so at most the row in flight is minted after the cancel, and its verdict/count is not recorded (the product exists; a re-run reports it `UNCHANGED`).
- **Audit.** `domain_events` rows for every ADMIN transition, `IMPORT_JOB_CREATED / VALIDATION_STARTED / REOPENED / APPROVED / RESUMED / CANCELLED` (aggregate `import_job`), plus the per-product events of every applied row. The worker's own phase ends (`VALIDATED`, `REJECTED`, `PAUSED`, `COMPLETED`) are recorded on the job (`status`, `lastError`, `finishedAt`), not as `domain_events`; row appends and corrections are not audited per row.

**Operations.** The worker runs on every instance with `tazzzo.scheduler.enabled=true` and `tazzzo.scheduler.import-jobs-enabled=true` (`TAZZZO_IMPORT_JOBS_ENABLED`), ticking every `import-jobs-tick-ms` (5 s). Throughput is the catalogue's own mint cost plus one small ledger transaction per row — NOT yet measured for a job; the synchronous import measured 25–40 ms per product in the capacity harness (PR #97), which would put a job in the order of tens of thousands of products an hour per worker. Validation is much faster. Storage: one `import_rows` document per row (the payload plus verdicts); the `import_jobs`/`import_rows` collections and their five indexes are created by migration `V0016`. Evidence: `ImportJobsIT` (CSV → REJECTED → corrected → VALIDATED → approved → COMPLETED → UNCHANGED re-run; a 621-row file across batches; pause on a datastore failure and resume without re-applying; cancel while a worker is minting; a lease lost mid-apply; a pause after the lease was lost; a product appearing between re-validation and mint; a same-millisecond renewal; validation blocked during an upload and an upload whose job left OPEN; a genuine collision with another product at mint time; an expired upload's lock ended by a state change; a correction keeping the other rows' verdicts; identities unique across appends; stale versions, limits, expired leases and the append lock; authorisation), `ImportCsvParserTest`.

## Not covered
- **Prices and stock as jobs.** Only products have the asynchronous job today; prices and inventory stay on the synchronous 500-row calls (a job kind for each is the natural next step on the same engine).
- **Purging old jobs.** Completed and cancelled jobs and their rows are retained; the retention window is a production-policy decision (`DATABASE_RETENTION_AND_PII.md`).
- **Body size.** `tazzzo.http.bulk-import-max-request-body-bytes` (2 MiB) bounds every `/api/v1/admin/imports/` request, including a job's `rows` request; split larger files.
