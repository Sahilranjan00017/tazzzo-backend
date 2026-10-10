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
1. **Prepare the products file.** One row per SKU in the single-create shape above, at most 500 rows per file, classified against the active release (`GET /api/v1/taxonomy/releases/{id}` reads one release by id; there is no list-releases route, so the release id is supplied out of band).
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

**CSV.** The header names the columns, matched by the CMS import wizard's aliases (case/punctuation-insensitive): `id` (`productid`, `tzpid`, `sku`), `title` (`name`), `brand`, `gtin` (`barcode`, `ean`, `upc`), `market` (default `IN`), `internalKey` (`key`), `vertical`, `release`, `classification` (`status`; default `provisional`), and any `attr.<name>` column (an integer, decimal or `true`/`false` cell is carried typed, exactly as a JSON row would be). Identity is `gtin` when a GTIN is present, else `internal`. RFC 4180 quoting (a UTF-8 BOM is tolerated); a malformed or over-limit file (bad quote, a cell over 4,000 characters, more than 64 columns, a request body over the size limit, the row cap) is refused as a whole (`422 INVALID_IMPORT` naming the kind of defect — not the line; `413` for the body limit) and **leaves the job exactly as it was**: the upload is atomic, the rows it had stored are removed and `rowsTotal` is untouched, so the corrected file can simply be uploaded again (it is never flagged `DUPLICATE_ROW` by the failed attempt). Product ids are taken verbatim: **no case normalisation** (`TZP-med-3` stays `TZP-med-3`; `TZP-a` and `TZP-A` are two different rows); the reader does not judge ids; the canonical grammar is enforced per row at validation (next paragraph). Only the brand code is upper-cased. Only one upload at a time per job (a second concurrent `rows` request is `409`). The `attr.*` columns are a server-side extension: the CMS wizard sends no attributes today. Text is read as UTF-8; other encodings are not detected.

**Product id grammar.** Every row's id must match the canonical `^TZP-[A-Za-z0-9-]{1,40}$` (`catalog/domain/ProductIds`, applied by `ProductImportValidator.shape()` for both the synchronous import and jobs): a full match, no case normalisation, so `TZP-Med-3`, `TZP-med-3` and `TZP-MED-3` are three valid, distinct products, while `TZP-a_b`, `TZP-`, `TZP-` plus 41 characters, `TZP-1` followed by a newline, `tzp-1` and `TZP-a b` are `INVALID` / `INVALID_ROW` at validation. Such rows appear in `errors.csv`, keep the job `REJECTED` (it cannot be applied), and are fixed with `PUT .../rows/{row}` to a valid id, after which the job re-validates and can be approved.

**Rules that hold at any size.**
- **One row per product id, GTIN or internal key per job** (the internal key only for an `internal`-identity row, as the catalogue ignores a GTIN product's internal key) is enforced by two partial unique indexes (`import_rows_one_per_product`, `import_rows_one_per_identity`) at ingestion — across appends and across validation batches; a later row with the same identity is kept, already `DUPLICATE`, and makes the job `REJECTED`. Canonical identity (brand + vertical + identity attributes) is still checked within each validation batch only; a cross-batch canonical duplicate surfaces at apply as a `FAILED` row.
- **Resumable, with an exact ledger.** Validation records each batch's verdicts with the cursor and counters in ONE lease-guarded transaction (`import-jobs-batch-size`, default 500), so a worker that lost its lease writes no verdict over the new holder's. Apply renews the lease BEFORE every mint and records each row's verdict, the cursor and the counters in ONE lease-guarded transaction AFTER it: a worker that lost its lease (expired, or the job was cancelled) stops at the next row, and the one row it may have minted meanwhile is recorded by nobody — the next holder re-reads it and finds the product `UNCHANGED` (also when its own mint then reports an identity collision: the row is re-checked and recorded `UNCHANGED`, not `FAILED`). A datastore failure pauses the job in ONE lease-guarded transaction too (row `NOT_ATTEMPTED`, cursor on it, `PAUSED`); a worker that lost its lease meanwhile writes nothing. Counts and verdicts therefore never disagree: `valid`/`unchanged`/`invalid`/`duplicate` partition the rows, and a row the apply finds already present moves from `valid` to `unchanged` (an `UNCHANGED` row it minted or failed moves the other way), so a `COMPLETED` job has `applied + failed == valid`; a product is never minted twice. A `PAUSED` job resumes from the cursor; a re-run of the same file is `UNCHANGED` row by row. If a job's row ledger turns out shorter than `rowsTotal` (a row document was lost — `rowsTotal` only ever counts stored rows), the worker ends the phase with `lastError` "row ledger short" instead of waiting forever. Verdicts are never bulk-cleared: every validation pass (from `OPEN` or `REJECTED`) re-verdicts every row from row 0, so while a job is `VALIDATING` a row the cursor has not reached still shows the previous pass's verdict, and after a correction (`REJECTED` → `OPEN`) the other rows keep theirs, so the admin still sees what is left to fix; the job's `counts` likewise stay those of the last pass until the next pass starts (it zeroes them). A row marked `DUPLICATE` at ingestion stays `DUPLICATE` (it is not re-validated) even if the row it clashed with is corrected away — correct that row too (`PUT …/rows/{row}`). A correction holds the job's append lock, so it never interleaves with an upload or with the start of validation (`409` while one is in progress).
- **Explicit approval.** The apply never starts on its own: `apply` records `approvedBy`, and every product event of the run is attributed to that admin.
- **Bounded.** A request body is still bounded by `tazzzo.http.bulk-import-max-request-body-bytes` (2 MiB by default, about 20k CSV rows): a larger file is appended in several `rows` requests. A job holds at most `tazzzo.imports.max-rows-per-job` rows (250,000) and at most `tazzzo.imports.max-active-jobs` (10) jobs are open at once.
- **Concurrency.** Every admin transition is a compare-and-swap on the job's `version` (send `{"version": n}` to refuse a stale decision); one worker at a time holds a job's lease (`import-jobs-lease-ms`, 300 s — longer than the driver's ~120 s retry budget of one mint transaction — renewed per batch and before every mint; held means the token matches, so a renewal that rewrites identical values is still a hold). A worker that crashes or whose tick fails does not release its lease, so another instance resumes that job after up to 300 s and a tick stops after `import-jobs-tick-budget-ms` (30 s) so other jobs get their turn.
- **Uploads.** One at a time per job (the append lock, 15 min); validation cannot start while an upload holds it (`409`), and an upload numbers its rows from `rowsTotal` as read under the lock; every state change ends the lock, so an upload whose job left `OPEN` meanwhile (cancelled, or validated after the lock expired) is refused with `409` and its rows are removed — no phase reads past `rowsTotal`. An upload publishes its rows only when it completes (one `rowsTotal` update, which also bumps the job `version`, so a client holding the old version is refused); any failure removes just that upload's row range (`rowsTotal` onward), and only while it still holds the append lock, so it can never delete another uploader's rows. The lock is renewed every 50 rows: an upload that loses it (the job left `OPEN`, or it was taken over) fails cleanly with `409` instead of publishing. A correction also bumps `version`. If the process dies mid-upload, its unpublished rows are removed by the next upload to the job (under the lock) before it numbers its own. Known limits: (1) the lock-ownership check and the rollback delete are two operations, so a lock that is lost in the milliseconds between them is not detected (the 15-minute lock plus the per-50-row renewal make this very unlikely; closing it would need per-row fencing tokens, a larger redesign); (2) the `text/csv` form of `POST …/rows` shares one OpenAPI operation with the JSON form (OpenAPI lists one POST per path): `docs/openapi.json` lists both request media types on `appendImportJobRows`, and `errors.csv` is documented as `text/csv`; (3) `errors.csv` has no pagination, it streams every negative row in 500-row pages, each page query bounded to 30 s; the `200` status and headers are sent before the first page is read, so if a page query exceeds that bound the response ends early with no error status and the file is silently truncated. A client must treat an `errors.csv` download as complete only if it was read to the end without a connection error, and for very large jobs should compare the row count with `counts` on the job; (4) the final lock check before an upload's rollback delete is a separate operation from the delete (see (1)); an upload that finds its lock lost at the end deletes nothing and answers `409`, leaving the new holder's rows intact.
- **Cancel.** From any non-terminal state; a running worker notices at its next lease renewal — before its next mint — so at most the row in flight is minted after the cancel, and its verdict/count is not recorded (the product exists; a re-run reports it `UNCHANGED`).
- **errors.csv.** One line per negative row (`INVALID`, `DUPLICATE` or `FAILED`), in strictly ascending row order, in a single pass over the job's rows (500 rows per page, each page bounded by a 30 s server-side `maxTime`); a row that failed at apply shows its apply verdict, otherwise its validation verdict. Cells that start with `=`, `+`, `-`, `@`, a tab or a carriage return are prefixed with a single quote so a spreadsheet never evaluates an uploaded value (OWASP CSV injection); the leading quote is part of the exported text.
- **Audit.** `domain_events` rows for every ADMIN transition, `IMPORT_JOB_CREATED / VALIDATION_STARTED / REOPENED / APPROVED / RESUMED / CANCELLED` (aggregate `import_job`), plus the per-product events of every applied row. The worker's own phase ends (`VALIDATED`, `REJECTED`, `PAUSED`, `COMPLETED`) are recorded on the job (`status`, `lastError`, `finishedAt`), not as `domain_events`; row appends and corrections are not audited per row.

**Operations.** The worker runs on every instance with `tazzzo.scheduler.enabled=true` and `tazzzo.scheduler.import-jobs-enabled=true` (`TAZZZO_IMPORT_JOBS_ENABLED`), ticking every `import-jobs-tick-ms` (5 s). Throughput is the catalogue's own mint cost plus one small ledger transaction per row — NOT yet measured for a job; the synchronous import measured 25–40 ms per product in the capacity harness (PR #97), which would put a job in the order of tens of thousands of products an hour per worker. Validation is much faster. Storage: one `import_rows` document per row (the payload plus verdicts); the `import_jobs`/`import_rows` collections and their five indexes are created by migration `V0016`. Evidence: `ImportJobsIT` (CSV → REJECTED → corrected → VALIDATED → approved → COMPLETED → UNCHANGED re-run; a 621-row file across batches; pause on a datastore failure and resume without re-applying; cancel while a worker is minting; a lease lost mid-apply; a pause after the lease was lost; a product appearing between re-validation and mint; a same-millisecond renewal; validation blocked during an upload and an upload whose job left OPEN; a genuine collision with another product at mint time; an expired upload's lock ended by a state change; a correction keeping the other rows' verdicts; identities unique across appends; stale versions, limits, expired leases and the append lock; authorisation), `ImportCsvParserTest`.

## Not covered
- **Prices and stock as jobs.** Only products have the asynchronous job today; prices and inventory stay on the synchronous 500-row calls (a job kind for each is the natural next step on the same engine).
- **Purging old jobs.** Completed and cancelled jobs and their rows are retained; the retention window is a production-policy decision (`DATABASE_RETENTION_AND_PII.md`).
- **Body size.** `tazzzo.http.bulk-import-max-request-body-bytes` (2 MiB) bounds every `/api/v1/admin/imports/` request, including a job's `rows` request; split larger files.
