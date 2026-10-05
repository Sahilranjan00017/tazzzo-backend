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

## Not covered
- **CSV upload and async/background jobs.** A 500-row JSON file applies synchronously, in seconds for prices and stock and about 21 s for products.
- **Body size.** The platform request-body limit (#55) must admit a 500-row file (about 100 KB). Check the configured limit when both are merged.
