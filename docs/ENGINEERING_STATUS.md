# Tazzzo Backend — Engineering Status

Single source of truth for what is actually built and verified in `tazzzo-backend`.
Reflects **current reality only** — nothing is marked complete unless verified from existing code.

Last updated: 2026-09-26

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

`main` = `4560a55360b5f25df069f1d47c4671628510f76f`.

## In review (NOT merged)

- **PR-10B — Public commerce read API** (`commerce.api`): the public `/v1` surface goes live —
  `CommerceReadController` (categories, children, category-products, product detail,
  serviceability) delegating to reused consumer taxonomy + new commerce.read services;
  `SurfaceClassifier` exposes `/v1` as PUBLIC_CONSUMER; `RuntimeToDtoMapper` (omit unsupported,
  fail-fast on required); list uses batch canonical price + `CurrentPriceOverlay`; PDP overlays
  current price + release-scoped reachability; serviceability threads `serviceAreaVersion` (never
  fulfillmentLocationId); commerce-route signed cursor; `CommerceExceptionHandler` → frozen
  `ErrorEnvelopeDto`; freshness-readiness gate on lists; no request-time writes. Frozen `/v1`
  OpenAPI corrected (404 on categories, 400 on products, internal projection schema removed);
  **OPENAPI-INTERNAL-PROJECTION-DEBT CLOSED**. No response cache / no AWS / no app integration
  (PR-10C). In review on `feature/pr10b-public-commerce-api`.
  **Final production hardening pass:** the commerce cursor now binds a non-reversible
  `fingerprint` of the request's normalized location (anonymous vs PIN) into the signed
  payload, so a page continuation under a different location/routing context is `INVALID_CURSOR`
  rather than silently served (`ConsumerCursorCodec.LOCATION_ANONYMOUS`/`LOCATION_UNBOUND`); the
  legacy `/catalog/v1` taxonomy walk and the public commerce categories/children now share the ONE
  computed-unit-cost implementation while charging distinct admission labels
  (`ConsumerTaxonomyService.root/children(..., Route)`); `commercePricingService`/
  `commerceMediaService` are wired to the shared `ProjectionRebuildQueue` via an
  `ObjectProvider` so a future writer through either bean cannot bypass freshness;
  `serviceAreaVersion` is `long` end-to-end (no int32 narrowing); a `DomainReadGuard` in
  `commerce.read` translates Pricing/Inventory/Media/Serviceability domain exceptions and Mongo
  outages into `SERVICE_UNAVAILABLE` (never an unmapped 500) at the PDP/list/serviceability seams;
  `requestId()` fails fast rather than ever returning the literal string `"null"`. Regression grew
  to **989 tests**, 0 failures/errors/skipped, on this pass.
- **PR-10C — Cache / observability hardening** (planned).

## Blocked

- (none tracked)

## Tracked debt

- See [`docs/architecture/DEBT-REGISTER.md`](architecture/DEBT-REGISTER.md). Currently OPEN:
  **OPENAPI-INTERNAL-PROJECTION-DEBT** — the frozen `/v1` file's internal
  `ProductCardBaseProjection` schema has drifted from the implemented projection;
  **MUST FIX BEFORE PR-10** exposes any public endpoint.

## Next (ratified sequence)

1. **PR-09** — PDP / runtime detail composition (in review, builds on the `enrichOne` seam).
2. **PR-10** — Public API / gateway / cache / observability / freshness gate. Must close
   OPENAPI-INTERNAL-PROJECTION-DEBT; cache keys must derive from routing topology
   (serviceAreaId alone is proven insufficient).

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

- **2026-09-26** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 +
  Docker (MongoDB 7, Redis via Testcontainers), on `main` at merge commit `f9c8899`
  (post-PR-08 baseline): **BUILD SUCCESS**, **842 tests, 0 failures / 0 errors / 0 skipped**,
  ~2:00 min.
