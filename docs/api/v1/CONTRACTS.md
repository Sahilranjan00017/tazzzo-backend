# Tazzzo `/v1` Commerce Read — Contract Reference (Phase 3.1 freeze)

**Status:** implemented. Machine-readable spec: `openapi.yaml`, kept in lock-step with the served `/v1/**`
operations by `ApiContractParityIT` (drift in either direction fails the build). The admin/internal `/api/v1`
surface is generated into `services/catalog-service/docs/openapi.json`.

## Money contract (ADR-002)
- Amounts are **integer paise** as **int64**: `sellingPricePaise`, `mrpPaise`, `discountAmountPaise`.
- Currency is **INR** for initial production (implicit; a `currency` field may be added additively).
- **No floating-point money. No decimal strings for amounts.**
- The existing internal `price` integer (in `offers_current` / `price_events`) has an **ambiguous unit** and **MUST NOT be silently reinterpreted as paise**. New paise fields are populated only by new validated writes; the legacy field is deprecated only after data provenance is verified. `/v1` exposes only validated paise fields.
- `discountPercent` and `discountAmountPaise` are **computed server-side only**; clients never derive authoritative discounts.

## Location / serviceability contract (ADR-004, Phase 3.1)
Resolution precedence (first match wins):
1. **Authenticated user + selected serviceable address** → server resolves its `serviceAreaId` (client cannot override).
2. **`?pin=560047`** — 6-digit Indian PIN, the launch location mechanism; anonymous-safe.
3. **`?lat=…&lng=…`** — reserved; resolves to an area later (not required at launch).
4. **No location** → browse-only: identity/price/media render; `stockState=UNKNOWN`, `serviceable=null`, no ETA, `buyable=false`.

Rules:
- The client **never** supplies `fulfillmentLocationId`; it is **internal only**, derived server-side by Serviceability from the location.
- Inventory is **always** resolved for the server-selected fulfillment location.
- Changing PIN/address changes the response cache key ⇒ forced re-fetch of stock/serviceability; pagination resets.
- Every list/detail response echoes a `serviceArea` summary so the client knows which location answered.

## Error contract (ADR-010)
Flat envelope:
```json
{ "code": "RATE_LIMITED", "message": "Too many requests", "requestId": "rq_...", "retryable": true, "retryAfterSeconds": 2, "details": null }
```
Frozen codes: `INVALID_REQUEST` (400), `INVALID_CURSOR` (400), `NOT_FOUND` (404), `RATE_LIMITED` (429, +`Retry-After`), `SERVICE_UNAVAILABLE` (503, +`Retry-After`), `INTERNAL` (500).
- `message` is generic and safe; **no stack traces or internal service names**.
- The existing `/catalog/v1` consumer error `{code, message, request_id}` maps cleanly; `/v1` adds `retryable` and `retryAfterSeconds` (additive superset). `/catalog/v1` is not changed by this PR.

**Two spellings of the request id, per route family (documented, not unified).** Domain errors of `/v1/**` (commerce read, cart, checkout, orders, addresses, profile, OTP, session, support, account deletion, content) carry camelCase `requestId`. Errors written by platform filters and framework fallbacks on `/v1/**` (413 `PAYLOAD_TOO_LARGE`, an undecodable query string, an unmapped failure) and every `/catalog/v1/**` error carry snake_case `request_id` (`PlatformErrorEnvelope` in `openapi.yaml`). The admin `/api/**` surface uses the nested `{ "error": { "code", "message", "request_id" } }`. All three carry the same id as the `X-Request-Id` header. Converging `/v1` on one spelling would be a contract change and needs approval.

**Request body bounds.** 64 KiB (65,536 bytes) on every route by default (`tazzzo.http.max-request-body-bytes`); 2 MiB on the admin bulk-import routes under `/api/v1/admin/imports/**` (`tazzzo.http.bulk-import-max-request-body-bytes`); the configurable hard maximum is 16 MiB. A larger body is refused with `413 PAYLOAD_TOO_LARGE` before authentication, with `Connection: close`.

**Which file generated clients use.** `openapi.yaml` is the hand-written app contract (`/v1/**`, per-operation error codes, operation ids such as `getCategories`). `services/catalog-service/docs/openapi.json` is generated from the controllers (`OpenApiExportIT`, never hand-edited) and covers the admin/internal surface plus the same `/v1/**` operations under its own operation-id vocabulary (`commerceReadCategories`) with the same status sets (`OpenApiContractIT` pins them to this file).

## Pagination contract (ADR-009)
- **Opaque, signed cursors** (existing catalog cursor codec). Clients **MUST NOT parse** the cursor.
- `page_size` bounded **1..50, default 20**; out-of-range ⇒ `INVALID_REQUEST`.
- `nextCursor` is **omitted at end of list**; `hasMore = (nextCursor != null)`.
- Responses carry `resolvedReleaseId` and `requestId`; lists also carry `serviceArea`.
- **Location change ⇒ pagination reset** (new cursor domain). Invalid/expired cursor ⇒ `INVALID_CURSOR`; client resets to first page.
- Clients **dedupe by stable id** across pages (keyset can shift under a live catalog).

## Module boundary contract (ADR-001, ADR-003.1, ADR-013)
Approved dependency graph (modular monolith, one deployable):
```
catalog   pricing   inventory   media   serviceability      (domain peers)
    \________\_________\__________\__________/
                        (read ports / interfaces)
                              │
                        commerce.read      (composition)
                              │
                        commerce.api        (/v1 controllers + DTOs)
```
Rules (enforced by ArchUnit — see `docs/architecture/README.md`):
- Domain modules are **peers**; none depends on another at compile time (cross-domain reads go through read-port interfaces).
- **No domain module depends on `commerce.read`** (acyclic).
- `commerce.read` depends only on domain **read ports**, never domain internals.
- `commerce.api` depends only on `commerce.read`.
- The existing `com.tazzzo.catalog` package is **not renamed** in this PR.

## Explicitly NOT implemented by this contract
Pricing/Inventory/Media/Serviceability behavior, the commerce projection, the `/v1`
endpoints, KMP networking, database migrations, and any runtime change to `/catalog/v1`.
