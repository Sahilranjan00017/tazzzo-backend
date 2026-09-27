# Tazzzo Backend — Engineering Status

Single source of truth for what is actually built and verified in `tazzzo-backend`.
Reflects **current reality only** — nothing is marked complete unless verified from existing code.

Last updated: 2026-09-28

---

## Current

- **catalog-service** — the only deployable service in this repository, operated as a
  **production modular monolith**: the commerce domains below are real, tested modules
  *inside* this service, with boundaries enforced by ArchUnit. Standalone-service extraction
  is a FUTURE decision and is **not required** for production operation.
  - Language/runtime: **Java 21** (Temurin 21)
  - Framework: **Spring Boot 3.3.5**
  - Datastore: **MongoDB** (primary; event-sourced write path)
  - Cache/limiter: **Redis** (consumer rate limiter only)
  - Test rig: JUnit 5 + Testcontainers (MongoDB 7 replica set + Redis)

## Merged on `main`

`main` = `f9c88997c9fe6337e3e79188b510845befad65b5` — regression floor **842 tests green**.
PR-01 through PR-08 are **MERGED**:

- **PR-01** — Frozen commerce architecture: 15 ADRs + ADR-003.1 (`docs/architecture/`),
  frozen `/v1` OpenAPI contract (`docs/api/v1/`), ArchUnit module-boundary rules, backend CI.
- **PR-02** — Money (int64 paise, INR) + DTO/contract primitives (`commerce.contract`),
  Indian PIN rule `^[1-9][0-9]{5}$`.
- **PR-03** — **Pricing** domain module (`price_current` + price events, CAS writes,
  immediate-only pricing — Option A).
- **PR-04** — **Inventory** domain module (`(sku_id, fulfillment_location_id)` unique key,
  derived available/stockState, atomic oversell-safe reserve path).
- **PR-05** — **Media** domain module (references only: assetKey never URL at rest;
  `MediaUrlResolver` at the edge; no upload/S3/CDN provisioning).
- **PR-06** — **Serviceability** domain module (pincode → service area + INTERNAL fulfillment
  routing; non-product audit rail via `domain_events`).
- **PR-07** — **ProductCardBaseProjection** (`product_card_base`): derived/disposable,
  strictly location-agnostic, unified fresh-observation rebuild loop.
  **NOT LIVE** for serving until an explicit freshness mechanism ships (frozen gate).
- **PR-08** — **Runtime product enrichment** (`commerce.read`): internal
  `RuntimeProductCard/Page/ServiceArea` composer — one serviceability resolution per request,
  one batched inventory read per page (≤ `MAX_PAGE_SIZE=50`), frozen buyable rule,
  cross-location isolation proven. Squash merge `f9c8899`.
- **PR-09** — **Runtime product detail composition** (`commerce.read`): internal PDP composer
  sharing the ONE `ConsumerProductResolver` with the legacy consumer PDP (no forked
  merge/eligibility semantics), catalog-version freshness gate, no truncation of authoritative
  data. Squash merge `cc23c88`.
- **PR-10A** — **Production projection freshness foundation** (`commerce.read` + neutral queue):
  async work-queue-driven `product_card_base` rebuild (generation-guarded `ProjectionRebuildQueue`;
  Pricing/Media/Catalog source hooks; unique-lease worker; drift reconciler; gated scheduler),
  Pricing batch read + ephemeral `CurrentPriceOverlay`, CAS-guarded source-version watermark.
  Squash merge `4560a55` — **931-test regression floor**.
- **PR-10B** — **Public commerce read API** (`commerce.api`): the public `/v1` surface —
  `CommerceReadController` (categories, children, category-products, product detail,
  serviceability) delegating to reused consumer taxonomy + new commerce.read services;
  `SurfaceClassifier` exposes `/v1` as PUBLIC_CONSUMER; `RuntimeToDtoMapper` (omit unsupported,
  fail-fast on required); list uses batch canonical price + `CurrentPriceOverlay`; PDP overlays
  current price + release-scoped reachability; serviceability threads `serviceAreaVersion` as
  `long` end-to-end (never fulfillmentLocationId); commerce-route signed cursor preserving the
  legacy `/catalog/v1` 9-field wire format byte-for-byte while the commerce route binds a
  non-reversible location fingerprint into a 10th field (a location change on continuation is
  `INVALID_CURSOR`); `CommerceExceptionHandler` → frozen `ErrorEnvelopeDto`, with Mongo outages and
  Pricing/Inventory/Media/Serviceability domain exceptions (via `commerce.read`'s
  `DomainReadGuard`) mapped to `SERVICE_UNAVAILABLE` and unexpected programming failures left at
  500; freshness-readiness gate on lists; no request-time writes; `requestId()` fails fast rather
  than ever returning the literal string `"null"`. Frozen `/v1` OpenAPI corrected (404 on
  categories, 400 on products, internal projection schema removed) —
  **OPENAPI-INTERNAL-PROJECTION-DEBT CLOSED**. No response cache / no AWS / no app integration
  (deferred to PR-10C). Squash merge `25bbe7f` — **1003-test regression floor**.
- **PR-10C** — **Commerce cache and observability hardening** (`commerce.api`/`commerce.read`):
  operational hardening only, no public business-semantics change. Cache-header correctness (a
  real bug fixed: categories/children no longer risk inheriting a public cache header on an error
  response — `Cache-Control` is set only after success, and every error response forces
  `no-store`); deterministic, structurally-unambiguous `TaxonomyETag` for categories/children with
  correct RFC 7232 weak `If-None-Match` comparison; bounded `ConsumerObservability` failure
  classification (`Outcome.INTERNAL_ERROR` distinct from `UNAVAILABLE`, closed `FailureClass`
  vocabulary); `FreshnessObservability` (`catalog.repo`) instrumenting the PR-10A rebuild
  queue/worker/reconciler with a typed `RebuildResult` enum (no arbitrary-`String` tag API);
  `CommerceReadReadiness` internal readiness seam (`baseReady`/`listReady` split, no new public
  endpoint). No response cache, no AWS infra, no app integration. Squash merge `4718d51` —
  **1051-test regression floor**.
- **PR-11A** — **Customer authentication security foundation** (`com.tazzzo.auth`): a FOURTH
  HTTP surface, `CUSTOMER_AUTHENTICATED`, for `/v1/customer` + `/v1/customer/**`. The blanket
  `/v1/**` public rule is GONE — `SurfaceClassifier` now uses an explicit, per-family `/v1`
  public allowlist (`/v1/categories/**`, `/v1/products/**`, `/v1/serviceability` exact,
  `/v1/auth/**`); any other `/v1` path, including the bare `/v1` root, is `UNKNOWN` and denied
  by default until deliberately ratified — a future route is never public by accident.
  `/v1/auth/**` stays reserved PUBLIC for the future OTP/login/refresh/logout endpoints
  (PR-11B/11C — NOT implemented here). `CustomerPrincipal` (customerId + sessionId, opaque
  `CUS_*`/`SES_*` value objects, no phone/installationId/IP/email as identity).
  `CustomerAccessTokenCodec`: HMAC-SHA256 signed, length-prefixed (never delimiter-joined)
  versioned token binding version/customerId/sessionId/issuedAt/expiresAt; fail-closed key
  config (`tazzzo.customer-auth.access-token-hmac-key-b64`, no default); injected `Clock`;
  constant-time signature comparison; expired/future-issued (beyond a 30s tolerance) tokens
  rejected; a pathological signed epoch value fails closed as `MALFORMED_CLAIMS` rather than an
  uncaught exception; `issue()` rejects a zero/negative TTL as an issuer-contract bug.
  `CustomerAuthFilter` (`HIGHEST_PRECEDENCE + 2`, deterministically after `RequestIdFilter` and
  `ApiAuthFilter`) owns the `CUSTOMER_AUTHENTICATED` surface exclusively — `ApiAuthFilter`
  explicitly skips it (no CMS/read service token can authorize a customer route, and a customer
  token can never authorize `/api/**`); every failure flattens to one flat `401 UNAUTHENTICATED`
  with a generic `WWW-Authenticate: Bearer` challenge and `Cache-Control: no-store`, never
  revealing the internal rejection reason. `X-Tazzzo-Installation-Id` and client IP remain what
  they always were — anti-abuse dimensions only, proven unable to authorize or bind identity.
  **No OTP, no phone provider, no refresh/session persistence/revocation, no Profile/Address/
  Cart, no real `/v1/auth/**` or `/v1/customer/**` production endpoints yet** — PR-11A proves
  cryptographic and time validity only; session-lifecycle revocation is a PR-11C concern. Squash
  merges `375d02e` (foundation) + `0ecae78` (deny-by-default hardening) → `4ce798f` —
  **1142-test regression floor**.

`main` = `4ce798f013f6fa6380e1155e5b88461607b1e4a3` (PR-11A squash `4ce798f`).

## In review (NOT merged)

- (none tracked)

## Blocked

- (none tracked)

## Tracked debt

- See [`docs/architecture/DEBT-REGISTER.md`](architecture/DEBT-REGISTER.md). No open entries —
  `OPENAPI-INTERNAL-PROJECTION-DEBT` closed in PR-10B.
- **Deferred (PR-10C review):** `CommerceListService` still performs its own direct `products`
  membership Mongo read rather than sharing a Catalog read seam with `ConsumerProductListService`;
  extracting it would require touching `ConsumerListGuardIT`'s structural pin on the legacy
  surface, judged out of scope for both PR-10B and PR-10C. The read is guarded (`MongoException`
  → 503), not left unguarded. A future PR may extract the shared seam alongside updating that
  guard test.

## Milestones

- **Public Commerce Read (PR-10A/B/C): COMPLETE.** The public `/v1` read surface (categories,
  children, category-products, product detail, serviceability) is live on `main`, operationally
  hardened (cache/ETag/observability/readiness), with a 1051-test regression floor.
- **Customer Auth (PR-11A/B/C): IN PROGRESS. Do not mark authentication complete.**
  - **PR-11A — customer auth security boundary: MERGED.** Fourth HTTP surface + principal/
    token cryptographic verification foundation, deny-by-default `/v1` classification. No
    business auth flow yet.
  - **PR-11B — OTP challenge lifecycle + provider abstraction: PLANNED.** Not started.
  - **PR-11C — login/session/refresh/logout endpoints: PLANNED.** Not started. Session
    persistence and revocation do not exist before this lands.

## Next (ratified sequence)

1. **PR-11B** — OTP challenge lifecycle + provider abstraction (planned, not started).
2. **PR-11C** — login/session/refresh/logout endpoints (planned, not started).
3. **PR-11D+** — Customer/Profile/Address, Cart, Checkout, Orders, Search, Notifications, app
   integration, AWS infrastructure: not started, not scoped yet.

## Not started (honest boundary)

**Domain modules implemented inside catalog-service** (Pricing, Inventory, Media,
Serviceability, projection/read composition) **are built and tested** — see above. What does
NOT exist is any standalone extracted service. **No code exists** for:

- auth-service
- customer-service
- inventory-service *(extraction only — the Inventory domain module itself is implemented
  inside catalog-service, PR-04)*
- cart-service
- checkout-service
- order-service
- search-service
- notification-service

Do not invent implementation status for Auth/Cart/Order/Search/etc. Microservice extraction
is FUTURE work and not required for the production modular monolith.

> Note: catalogue CMS / attribute-governance / offers logic also lives **inside**
> `catalog-service`; any future extraction (e.g. a separate cms-service) is a design
> decision, not yet started.

## History

- Repository restructuring: `tazzzo-backend` created; `catalog-service` imported under
  `services/catalog-service` (history-preserving subtree, 39 commits, root `9807372` → `5e4ef5a`);
  migrated tree confirmed byte-identical to the original catalog repo (565/565 at migration).

## Last verification

- **2026-09-28** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 +
  Docker (MongoDB 7, Redis via Testcontainers), on `main` at squash merge `4ce798f`
  (post-PR-11A baseline): **BUILD SUCCESS**, **1142 tests, 0 failures / 0 errors / 0 skipped**.
  `backend-ci` green on the same commit (Compile & test, Validate API contracts).
- **2026-09-27** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 +
  Docker (MongoDB 7, Redis via Testcontainers), on `main` at squash merge `4718d51`
  (post-PR-10C baseline): **BUILD SUCCESS**, **1051 tests, 0 failures / 0 errors / 0 skipped**,
  ~1:45 min.
