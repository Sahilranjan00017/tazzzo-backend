# Final contract and API-completeness audit (Phase 5 + Phase 11)

- Date: 2026-10-10
- Backend baseline: `origin/main` d04cc875f7b1544b9a549dc8e08b10a1c8fdf595 (`tazzzo-backend`)
- Web baseline: `origin/main` 4a02f7710f3e3ecd68b123bcf5605568250a95d1 (`tazzzo-web`: `apps/admin` = CMS, `apps/storefront` = customer website)
- Scope: read-only analysis. No application behaviour changed. Two stale doc lines corrected in this branch (see L5).

## 0. How this was derived

1. Served routes: every `@Get/Post/Put/Delete/Patch/RequestMapping` in `services/catalog-service/src/main/java` (class prefix + method path, `consumes`/`produces`, `@Operation(hidden)`), resolving the two constant paths (`AccountDeletionController.PATH`, `DeliverySlotController.PATH`) by hand.
2. Running context: on the baseline, `OpenApiExportIT`, `ApiContractParityIT` (3 tests) and `OperationIdContractIT` were run through the Testcontainers setup (offline Maven, Java 21): **5 tests, 0 failures**. The regenerated `docs/openapi.json` is **byte-identical** to the committed `services/catalog-service/docs/openapi.json`, so the committed spec is not stale relative to the code.
3. Contract files: `services/catalog-service/docs/openapi.json` (generated, whole app), `docs/api/v1/openapi.yaml` (hand-written `/v1` + health).
4. Filters and handlers: `SurfaceClassifier`, `ApiAuthFilter`, `AdminAccessPolicy`, `CustomerAuthFilter`, `CustomerRateLimitFilter`, `RequestBodyLimitFilter`, `MalformedQueryFilter`, and the per-controller `*ExceptionHandler`s.
5. Clients: all of `apps/admin/src/server/{backend,bff}`, `apps/admin/src/lib/*` (zod schemas and path builders) and `apps/storefront/src/server/backend/*`.

What the existing ITs already guarantee (so not re-litigated): every served `/v1/**` method+path is in the YAML and vice versa (`ApiContractParityIT`); the product-id grammar equals `ProductIds.REGEX` in the YAML `ProductId`, the cart `skuId`, and four generated places (create body id, bundle/pack component ids, admin price/inventory/product path params); every documented 4xx/5xx in the YAML has a JSON body requiring `code` and `message`; operation ids in the generated spec are unique, non-numeric-suffixed and stable.

## 1. Headline counts

| Measure | Count |
|---|---|
| Controller handler methods (incl. 1 hidden text/csv overload and 1 same-path list overload) | 137 |
| **Routes served** (distinct method+path) | **135** |
| - INTERNAL `/api/**` (service token or Google OIDC human) | 88 |
| - PUBLIC_CONSUMER `/v1/**` (categories 4, search 1, product detail 1, batch 1, serviceability 1, content 3, auth 5) | 16 |
| - CUSTOMER_AUTHENTICATED `/v1/customer/**` (profile 2, addresses 6, cart 4, checkout 2, orders 4, delivery slots 1, support 5, account deletion 1) | 25 |
| - PUBLIC_CONSUMER legacy `/catalog/v1/**` | 4 |
| - HEALTH `/health/live`, `/health/ready` | 2 |
| **Documented in `openapi.json`** (operations) | **135** (all served routes have a path+method entry; but see M1/M2: one is a merged entry, 4 point at the wrong schema) |
| **Documented in `openapi.yaml`** | **43** (41 `/v1/**` + 2 health) |
| Served but undocumented (path+method, in either file) | 0 in `openapi.json`; 0 `/v1` in YAML; 4 `/catalog/v1/**` only in JSON |
| Documented but not served | 0 |
| Hidden by design | 1: `POST /api/v1/admin/imports/jobs/{id}/rows` as `text/csv` (`appendImportJobRowsCsv`, `@Operation(hidden = true)`; documented in `docs/ops/BULK_IMPORT.md`) |
| Operation ids: JSON unique | 135 / 135 (stable `<controller><Method>` plus explicit ids) |
| Operation ids: YAML unique | 40 distinct / 43 ops (3 ops have no id) |
| Admin ops with a CMS consumer | 60 of 88; 28 have none (section 5) |
| Admin routes the CMS calls that do not exist | 0 |

## 2. PART A: served vs documented

### 2.1 Route-table drift

- A1. `GET /api/v1/products` exists twice in code (`ProductController.list` and `getByCanonicalKey` with `params="canonicalKey"`). springdoc keeps one: the spec shows `productGetByCanonicalKey` with `canonicalKey` **required**, response `oneOf[ProductResponse, ProductListResponse]`, and the admin list parameters (`verticalId`, `lifecycle`, `status`, `cursor`, `limit`) are absent. The list operation the CMS depends on is effectively undocumented (M2).
- A2. `/catalog/v1/categories`, `/catalog/v1/categories/{nodeId}/children`, `/catalog/v1/categories/{nodeId}/products`, `/catalog/v1/products/{productId}` are served and in `openapi.json` only. They are the legacy mobile namespace (`SurfaceClassifier` Phase 4B.1) and overlap `/v1/categories*` and `/v1/products/{id}`. No consumer in either web app. Errors there use `request_id`.
- A3. Stale doc paths (not served): `GET /api/v1/taxonomy/releases` (`docs/ops/BULK_IMPORT.md:69`, **fixed in this branch**), `/v1/catalog` (`docs/ops/CUSTOMER_RATE_LIMITS.md:26`, **fixed**), `GET /v1/home` (ADR-008, historical; the live route is `/v1/content/home`; ADR left untouched).
- A4. No removed or renamed route is still referenced by code or by a client.

### 2.2 Surface and security vs what the filters enforce

| Surface (`SurfaceClassifier`) | Enforced by | Documented in YAML | Match |
|---|---|---|---|
| PUBLIC_CONSUMER (`/v1/{categories,products,search,serviceability,content/*,app-config,auth/*}`, `/catalog/v1/**`) | no credential; `ConsumerRateLimiter` by IP/installation/trusted caller | no `security` | yes |
| `POST /v1/auth/logout` (classified PUBLIC) | bearer verified inside `SessionController`, not by a filter | `customerBearerAuth`, 204/401 | yes (enforcement is in the controller; any future move of this route into the filter set must keep that) |
| CUSTOMER_AUTHENTICATED (`/v1/customer/**`) | `CustomerAuthFilter` (401/503), `CustomerRateLimitFilter` (429/503, read vs write bucket per customer) | `customerBearerAuth` on all 25 | yes, except 429 omitted on 9 ops (L1) |
| INTERNAL (`/api/**`, `/v3/api-docs*`) | `ApiAuthFilter` then `AdminAccessPolicy`: Google OIDC human (allowlisted, hosted-domain) or service token (`read`, `cms`); roles `reader`, `cms-writer`, `audit-reader`, `order-ops`, `support-agent`; `/orders` and `/support` namespaces are human-staff only | **nothing**: `openapi.json` has no `securitySchemes` and no `security` (M4) | documentation gap, not an enforcement gap |
| HEALTH | unauthenticated, exact paths | no `security`, 200/503 | yes |
| UNKNOWN (everything else) | 404 `NO_SUCH_ENDPOINT` before credentials are read | n/a | yes |

No route was found whose documented security is *weaker* than what the chain enforces.

### 2.3 Statuses and error paths (sampled 30 operations)

Sampled: product create/patch/lifecycle/merge, import (sync x3 and jobs create/rows/validate/apply/errors.csv), media uploads/PUT/GET, content block create/upload, price/inventory/service-area/delivery-window PUT, staff order transition, support reply, public product detail and batch, cart GET/PUT/DELETE, checkout quote, order place/cancel/get/list, address create/delete, OTP request, logout.

- **`openapi.json`**: every one of 135 operations declares exactly one response, `200`. Real success statuses that differ: 201 (product create, taxonomy release open and node create, attribute create, evidence create, content block create, content upload, media upload, import job create; and first-time PUT of price, inventory, service area, delivery window and media set, which are 201 vs 200 on update), 202 (product merge, evidence retract), 204 (logout, address delete). Admin error statuses (400/401/403/404/409/412/413/422/503) and the admin nested envelope are not described at all (M3).
- **`openapi.yaml`**: statuses match the controllers/handlers on all sampled `/v1` operations (OTP 202, address create 201 and delete 204, order place 200 with replay, support open 201, quote 410 `QUOTE_EXPIRED`, cart 409 `CART_ITEM_LIMIT_REACHED`, 412/428 on If-Match, 415, 406, 503). Drift: cancel-order 409 codes (M5); 429 missing on 9 customer ops (L1); 413 absent everywhere (M7).
- Import/jobs error paths (code): 404 `IMPORT_JOB_NOT_FOUND`, 409 `IMPORT_JOB_STATE`, 422 `INVALID_IMPORT` (rejected body carries `error` plus `errors[]`), 413 on bodies over 2 MiB, all in the admin nested envelope. Media: 404 `NOT_FOUND`, 409 `STALE_VERSION`, 422 `INVALID_MEDIA`, 503 `MEDIA_STORAGE_NOT_CONFIGURED` / `MEDIA_STORAGE_UNAVAILABLE`. None is in `openapi.json`.

### 2.4 Error envelope shapes

| Surface | Shape | Source |
|---|---|---|
| INTERNAL `/api/**` | `{ "error": { "code", "message", "request_id" } }` | `ApiAuthFilter.reject`, `ApiExceptionHandler.ErrorBody`, `RequestBodyLimitFilter` |
| `/v1/**` domain errors (commerce read, cart, checkout, orders, addresses, profile, OTP, session, support, deletion, content) | flat `{ code, message, requestId, ... }` (camelCase; commerce read adds `retryable`; OTP adds `retryAfterSeconds`; checkout adds `items`) | `*ErrorDto` records, `CommerceExceptionHandler` |
| `/v1/**` framework-level errors (413 from `RequestBodyLimitFilter`, 400 from `MalformedQueryFilter`, fallbacks in `ApiExceptionHandler.publicEnvelope`) and all of `/catalog/v1/**` | flat `{ code, message, request_id }` (snake_case, `ConsumerDtos.ConsumerError`) | M6 |

So "public flat uses `request_id`" is only true for the framework-level and legacy paths; the documented and domain-level spelling on `/v1` is `requestId`.

### 2.5 Bounds, ids, content types

- Body limit: 65,536 bytes on every surface (`tazzzo.http.max-request-body-bytes`), 2 MiB for `/api/v1/admin/imports/**` (500 rows per file), 413 `PAYLOAD_TOO_LARGE`, `Connection: close`. Not documented in either contract (M7).
- Other documented bounds present and correct in the YAML: `ids` 1..50 on batch with a full pattern and 2249-char cap, `page_size` 1..50, `q` 2..64 chars, cart quantity 1..20 (client side), `days` on slots.
- Canonical product-id pattern `^TZP-[A-Za-z0-9-]{1,40}$`: present on every admin product/price/inventory path param, `survivorId`, create body, bundle/pack components, YAML `ProductId`, cart `skuId` and batch `ids`. **Missing in the generated JSON** for `/v1/products/{id}`, `/catalog/v1/products/{productId}`, `/v1/customer/cart/items/{skuId}`, import `PriceRow.skuId` / `StockRow.skuId`, content `PayloadDto.ids[]`, media `{ownerId}` (and `{ownerType}` has no enum) (L2).
- Content types: generated spec lists `*/*` for 132 response bodies; real bodies are `application/json` and `/v1` negotiates (406). `errors.csv` has no `text/csv` content entry. The 14 request bodies typed as bare `JsonNode` are listed in M4.

## 3. PART B: clients vs contract

### 3.1 CMS (`apps/admin`)

Every path+method the BFF calls exists on main (0 calls to removed or renamed routes). Called: `GET /api/v1/admin/{me,audit-events,dashboard/summary,orders,orders/{id},support/cases,support/cases/{id},media/{t}/{id},prices/{sku},inventory/{sku}/{loc},service-areas,service-areas/{pin},delivery-slots/{area},content/blocks,content/blocks/{id},content/preview/home,app-config}`, `GET /api/v1/{products,products/{id},taxonomy/nodes,taxonomy/nodes/{id},taxonomy/nodes/{id}/path,taxonomy/releases/{id}}`, `GET /health/{live,ready}`, plus the POST/PUT/PATCH set (orders transition; support messages/assign/status; media uploads/PUT; price/inventory PUT and activate/deactivate; service-area and delivery-window PUT and activate/deactivate; content blocks create/update/status/reorder/uploads; app-config PUT; `imports/{products|prices|inventory}`; taxonomy nodes/releases create, rename/deprecate/revive, publish; product create, PATCH title, activate/retire/revive/archive). Request bodies, enums (`APP_ONLY|WEB_ONLY|BOTH`, `DRAFT|PUBLISHED|ARCHIVED`, `product|sku`, `OPEN|IN_PROGRESS|RESOLVED|CLOSED`), If-Match style (bare int for products, `expectedVersion` body elsewhere) and read-schema field names agree with the DTOs, **with this drift**:

| # | Drift | Evidence | Effect |
|---|---|---|---|
| B1 | `staffCaseSchema.messages[].id` is `z.string()`; backend sends an integer | web `apps/admin/src/lib/support.ts:81`; backend `SupportDtos.StaffMessage(int id, ...)` | case detail fails zod and renders "unavailable (shape)" for every case, since each is created with message id 1 (H1) |
| B2 | Reads use `.nullish()` for fields the generated spec marks nothing required; the spec cannot catch removals | `openapi.json` has `required` on 0 of 148 schemas | drift detection must come from backend tests, not codegen (M4) |
| B3 | Product list: CMS sends `verticalId/lifecycle/status/cursor/limit` and parses `{items[ProductSummary], nextCursor}`; the generated spec says the response is the consumer list (`items[ConsumerProductResponse]`, `next_cursor`, `resolved_release_id`) | M1/M2 | a client generated from the spec would be wrong; the hand-written zod is right |
| B4 | Taxonomy list and staff order list: spec points at `NodeListResponse` (commerce shape) and `StaffPage` (support shape) | M1 | same as B3 |
| B5 | `staffOrderSchema` reads `payablePaise` etc. correctly but cannot show benefit breakdown because the backend does not send one | M8 | feature gap, not a parse error |

### 3.2 Storefront (`apps/storefront`)

Calls: `GET /v1/content/home?channel=web`, `/v1/products/{id}`, `/v1/products:batch`, `/v1/categories`, `/v1/categories/{id}`, `/v1/categories/{id}/children`, `/v1/categories/{id}/products`, `/v1/search`, `/v1/serviceability`; `POST /v1/auth/{otp/request,otp/verify,session,refresh,logout}`; `GET /v1/customer/profile`; address list/get/create; cart GET/PUT/DELETE item/DELETE; `POST /v1/customer/checkout/quote`; orders list/get/place/cancel; `GET /v1/customer/delivery/slots`. All exist, all methods match, required params and headers are sent (`If-Match`, `Idempotency-Key`, `pin`, `ids`, `q`), `channel=web` is within the documented `app|web` enum, the batch cap (50) equals the contract, and the response schemas (orders, cart, checkout, slots, addresses, serviceability, batch) agree field-for-field with the YAML (checked by key-set comparison and by reading orders, slots and cart in full).

Drift: B6 `orders.ts` switches on `ORDER_NOT_CANCELLABLE` and `CANCELLATION_WINDOW_CLOSED`, which the YAML enum for the order envelope omits (M5); B7 the storefront's own README (`Backend gaps` 9) already notes that `openapi.json` is too thin for cart/addresses, which M3/M4 confirm. Storefront does not call profile PATCH, address PATCH/DELETE/default, checkout quote GET, support, account deletion, `/v1/content/faqs`, `/v1/app-config`: contract-only today (consumer is the mobile app or a later web slice; not gaps).

## 4. Findings

Severity: HIGH = a shipped consumer is broken or will be on first real data; MEDIUM = contract or capability wrong in a way that misleads generated clients or blocks an evidenced business need; LOW = documentation precision.

| ID | Sev | Finding | Evidence | Proposed fix | Size |
|---|---|---|---|---|---|
| H1 | HIGH | CMS support case detail cannot parse messages: `id` is a string in zod, integer on the wire. Every case fails (each is created with an opening message) | web `apps/admin/src/lib/support.ts:81`; backend `support/SupportDtos.java:33` | web PR: `id: z.number().int()` (or `z.union([z.number(), z.string()])`), add a fixture with a message | XS |
| M1 | MEDIUM | Schema simple-name collisions in the generated spec: `GET /api/v1/products` -> consumer `ProductListResponse`; `GET /api/v1/taxonomy/nodes` -> commerce `NodeListResponse`; `GET /api/v1/admin/orders` -> `StaffPage` of support summaries. Also `Item`, `Line`, `Page`, `Price` are shared by unrelated DTOs | `openapi.json` (`ProductListResponse`, `NodeListResponse`, `StaffPage` property lists vs `ApiDtos.java:122,116`, `StaffOrderController.java:62`, `SupportDtos.java:54`) | `@Schema(name = "AdminProductListResponse")` etc. on the colliding records, or springdoc `use-fqn`; add an IT that fails on a duplicate simple name | S |
| M2 | MEDIUM | Admin product list is not a documented operation (same method+path as the canonicalKey lookup); `canonicalKey` shown as required | `ProductController.java:85,105`; `openapi.json` `/api/v1/products` | document one operation with optional `canonicalKey` and both response shapes via `@Operation`/`@Parameter(required=false)`, or give the lookup its own path in a contract-approved change | S |
| M3 | MEDIUM | `openapi.json` documents only `200` for all 135 operations: wrong success codes on ~17 ops (201/202), no error responses, no admin envelope | counts in 2.3 | a global `OperationCustomizer` adding the per-surface error responses and envelope, plus `@ApiResponse(201)` on the create/upsert handlers | M |
| M4 | MEDIUM | Generated spec has no `required`/nullable on any schema, no `securitySchemes`/`security`, `info` is the springdoc default, and 14 request bodies are bare `JsonNode` (admin: support reply/assign/status, order transition; customer: cart set item, quote, order place, cancel, profile and address PATCH, support open/reply, account deletion) | `openapi.json` | introduce small typed request records (or `@Schema(implementation=)`), `@Schema(requiredMode=REQUIRED)` on non-null fields, a bearer scheme for INTERNAL, real `info` | M |
| M5 | MEDIUM | YAML order error envelope omits codes the cancel route emits: `ORDER_NOT_CANCELLABLE`, `CANCELLATION_WINDOW_CLOSED`, `INVALID_TRANSITION`, `STALE_VERSION`, `DELIVERY_SLOT_UNAVAILABLE` (the last only in prose). Cancel-order 409 references `OrderConflict`, which lists place-order codes only. Storefront already depends on the first two | `OrderExceptionHandler.java:39-50`; `openapi.yaml` `CustomerOrderErrorEnvelope` enum, `OrderConflict` | extend the enum, add a `CancelConflict` response; extend `ApiContractParityIT` to compare handler codes with enums | S |
| M6 | MEDIUM | `/v1` error bodies use `requestId` (domain) or `request_id` (413, malformed query, framework fallbacks, `/catalog/v1`) | `ConsumerDtos.java:68`, `RequestBodyLimitFilter.reject`, `MalformedQueryFilter:90-95` vs `*ErrorDto` | document both today (S); converging on `requestId` for `/v1` is a contract change needing approval (M) | S / M |
| M7 | MEDIUM | 413 `PAYLOAD_TOO_LARGE` and the 64 KiB / 2 MiB bounds are in no contract file | `HttpPlatformProperties.java:24-27`, `application.yml:291-293` | add a shared 413 response and a note in `CONTRACTS.md` / `info.description` | XS |
| M8 | MEDIUM | Staff order detail has no benefit/discount breakdown or status timeline, although the customer order has `money` and an order benefit snapshot exists (user checklist: ORDERS "benefits snapshot") | `StaffOrderController.StaffOrder` (no money fields) vs `CustomerOrderDto.money`; web `CMS_BACKEND_CONTRACT_MATRIX.md` gap 6 | additive fields `money{merchandiseSubtotalPaise,benefitDiscountPaise,payablePaise}` and `benefitSnapshot` on the staff order; additive, but needs approval as a response-shape change | S |
| M9 | MEDIUM | No price history: `PRICE_UPDATED` ledger events exist, but `/admin/audit-events` returns no before/after `detail`, so "pricing history" (user checklist) cannot be shown | `PricingService.java:133`, `AuditEventDto` fields | additive `GET /api/v1/admin/prices/{skuId}/history` (paged, from the ledger) or an opt-in `detail` on audit events for `audit-reader` | M |
| M10 | MEDIUM (consumer) | CMS has no consumer for `GET /api/v1/admin/inventory` (`listStock`, with `stockState`), so low/out-of-stock queues cannot be built | section 5 | web PR: inventory list page using the existing route | M |
| L1 | LOW | 9 customer ops omit 429: orders list, delivery slots, order cancel, all 5 support ops, account deletion | `CustomerRateLimitFilter` covers all `/v1/customer/**` | add `CustomerRateLimited` | XS |
| L2 | LOW | Product-id pattern absent in generated JSON in 7 places (2.5) | `openapi.json` | `@Schema(pattern = ProductIds.REGEX)`; extend `the_published_product_id_patterns_equal_the_java_grammar` | XS |
| L3 | LOW | YAML: 3 ops with no `operationId` (both health probes, account deletion); YAML and JSON use different id vocabularies (`getCategories` vs `commerceReadCategories`) | `openapi.yaml` | add ids; state in `CONTRACTS.md` which file generated clients should use | XS |
| L4 | LOW | `/catalog/v1/**` (4 routes) is outside the app contract and duplicates `/v1/categories*` / `/v1/products/{id}` | 2.1 A2 | decide: document in YAML as legacy, or deprecate with a date | XS / S |
| L5 | LOW | Stale doc lines | 2.1 A3 | `BULK_IMPORT.md:69` and `CUSTOMER_RATE_LIMITS.md:26` corrected in this branch; ADR-008 left | done |
| L6 | LOW | Response content is `*/*` for 132 ops; `errors.csv` has no `text/csv` entry; hidden csv append only in prose | `openapi.json` | `produces = application/json` on the controllers; `@ApiResponse(content = text/csv)` | S |
| L7 | LOW | Admin product list: `lifecycle`/`status` filters require `verticalId` (400), so there is no "all drafts" queue; list rows carry no price/stock/media | `ProductController.java:86-88` | known (web matrix); only act if the CMS merchandising flow needs it | S |
| L8 | LOW | Admin IDs: media `{ownerType}` free string (valid `product|sku`), no enum in spec | `MediaAdminController` | `@Schema(allowableValues)` | XS |

## 5. Admin routes with no CMS consumer (28) and whether they are needed

| Routes | Judgement |
|---|---|
| `GET /admin/inventory` (`listStock`) | **Needed**: low/out-of-stock operations (M10). Backend complete; CMS missing |
| `/admin/imports/jobs*` (10: create, rows POST/PUT/GET, validate, apply, resume, cancel, list, get, errors.csv) | Keep. Async path for files above the sync 500-row cap and for approval workflows. CMS uses sync only; add a CMS slice only when volumes require it |
| `GET /admin/delivery-slots/{area}/{window}` | Redundant (list returns the same rows). Keep, no action |
| `POST /products/{id}/classify`, `/gtins`, `/merge/{survivor}`, `POST /products/{id}/publish` (claim publish) | Data-governance and importer tools. `classify` and `merge` are plausible operator actions later; no evidenced CMS need now |
| `POST /taxonomy/nodes/{id}/move|merge|split` | Structural taxonomy refactors; CMS offers rename/deprecate/revive only. No need evidenced |
| `/attributes*`, `/attribute-schemas*`, `/evidence*` (8) | Internal authoring/importer APIs. No CMS need |
| `GET /products?canonicalKey=` | Importer identity lookup |

## 6. PART C: required API checklist

| Area | Verdict | Route evidence | Consumer |
|---|---|---|---|
| AUTH: customer | COMPLETE | `POST /v1/auth/otp/request`, `/otp/verify`, `/session`, `/refresh`, `/logout`; `CustomerAuthFilter` | storefront (all five), mobile |
| AUTH: admin | COMPLETE | `GET /api/v1/admin/me`; Google OIDC human + service tokens (`read`, `cms`); roles `reader`, `cms-writer`, `audit-reader`, `order-ops`, `support-agent`; `/orders` and `/support` human-only | CMS |
| CATALOGUE | COMPLETE with caveats | create `POST /products` (single, bundle `bundleContents`, variant pack `packOf`); edit `PATCH /products/{id}` (title only); lifecycle `activate|retire|revive|archive`; taxonomy node CRUD + release open/publish; public `GET /v1/categories`, `/{id}`, `/{id}/children`, `/{id}/products`; `GET /v1/products/{id}`; `GET /v1/search`; `GET /v1/products:batch` | CMS (single create + lifecycle only), storefront. Caveats: edits beyond title and product facets/sort have no route and no evidenced consumer request; CMS does not call bundle/pack creation (needs component products first) |
| PRICING | GAP (history only) | `GET/PUT /admin/prices/{sku}`; customer-facing price and MRP in product, cart and quote DTOs | CMS, storefront. Gap: M9 |
| INVENTORY | COMPLETE (CMS consumer gap) | `GET/PUT /admin/inventory/{sku}/{loc}`, `activate/deactivate`, `GET /admin/inventory` with `stockState`; reservation at order placement | CMS reads/writes; list not consumed (M10) |
| IMPORT | COMPLETE | sync `POST /admin/imports/{products,prices,inventory}` (dryRun, 500 rows); jobs: create, rows (JSON + hidden text/csv), correct row, validate, apply, status, cancel, resume, `errors.csv` | CMS uses sync; jobs have no UI |
| MEDIA | COMPLETE (provider config external) | `POST /admin/media/uploads` (presign), `PUT/GET /admin/media/{ownerType}/{ownerId}` (verify + set), resolution in product/PDP/cart DTOs, `S3MediaStorage` present | CMS, storefront. Storage bucket/CDN base URL is environment configuration (NOT VERIFIED end to end here) |
| CONTENT | COMPLETE | blocks list/get/create/update/status/reorder, `content/uploads`, `preview/home`, `app-config` GET/PUT; types BANNER, PRODUCT_RAIL, CATEGORY_GRID, FAQ; `startsAt`/`endsAt`; `audience` `APP_ONLY|WEB_ONLY|BOTH`; public `/v1/content/home?channel=app|web`, `/content/faqs`, `/app-config` (legal URLs) | CMS (all), storefront (home) |
| CART | COMPLETE | `GET /v1/customer/cart` (freshness, `buyable`, `issues`, price/stock revalidation, `addressId`), `PUT` and `DELETE /items/{sku}`, `DELETE /cart`; If-Match versioning gives stale-cart detection | storefront |
| CHECKOUT | COMPLETE | addresses (6 routes), `GET /v1/customer/delivery/slots`, `POST /checkout/quote` (If-Match + Idempotency-Key), `GET /checkout/quotes/{id}`; COD only | storefront (quote GET unused) |
| ORDERS | COMPLETE (staff view gap M8) | `POST /v1/customer/orders` (idempotent by `quoteId`, replay returns 200 same order), list, detail, `POST .../cancel` (configurable window), `money` with benefit discount; staff `GET /admin/orders`, detail, `POST .../transition` | storefront, CMS |
| NOTIFICATIONS | COMPLETE for scope | no HTTP routes by design: transactional outbox enqueue, dispatcher, dev-only sandbox sender, dashboard pending/failed counts. Real vendor adapter: EXTERNAL_CONFIG_PENDING (`docs/ops/NOTIFICATIONS.md`) | internal |
| ADMIN | COMPLETE (28 routes without CMS consumer, section 5) | dashboard, audit-events, support staff routes (list/get/reply/assign/status), service areas, delivery windows, orders ops, content, imports | CMS |

## 7. Recommended PRs

Backend (docs/contract; no behaviour change unless stated):
1. **Contract hygiene PR** (M1, M2, M7, L2, L8; S): unique schema names, one documented admin product list op, shared 413, id patterns, plus ITs for duplicate schema names and handler-code-to-enum parity.
2. **Contract completeness PR** (M3, M4, L6; M): success/error responses and envelopes in the generated spec, typed request records replacing `JsonNode`, security scheme and `info`.
3. **YAML correctness PR** (M5, M6 documentation half, L1, L3, L4; S): order cancel codes, 429 on 9 ops, operation ids, `request_id` note.
4. **Staff order money/benefit snapshot** (M8; S; additive response change, needs approval).
5. **Price history read** (M9; M; additive, needs approval).

Web:
6. **H1 fix** in `apps/admin` (XS) - do first.
7. **CMS inventory list page** over `listStock` (M10; M).

## 8. NOT VERIFIED

- Runtime responses were not exercised over HTTP for the sampled routes; status and code claims come from controllers and handlers plus the three passing ITs.
- H1 was established from code (every case is created with message id 1 in `SupportService.java:85`, and the id is an `int`), not reproduced against a running backend.
- Real object storage, CDN base URL, OTP gateway, geo provider and notification vendors (all external configuration).
- Deep field-by-field check of storefront catalog/PDP/home zod against the YAML was a key-set comparison, not a full structural diff.
- Web-side generated-client use: neither app uses generated clients, so spec-shape errors (M1-M4) are latent for them.
