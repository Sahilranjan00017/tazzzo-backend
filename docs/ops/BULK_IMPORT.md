# Bulk price and stock import

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

## Not covered
- **CSV upload, product creation in bulk, and async/background jobs.** Products need classification evidence, so they keep the single-product API. A 500-row JSON file applies synchronously in seconds.
- **Body size.** The platform request-body limit (#55) must admit a 500-row file (about 100 KB). Check the configured limit when both are merged.
