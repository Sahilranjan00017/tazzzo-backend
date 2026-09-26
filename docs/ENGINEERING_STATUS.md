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

`main` = `d2799eef8070e7ae6a31a92ae08e28cdf44931cd` — regression floor **773 tests green**
(pre-PR-08). PR-01 through PR-07 are **MERGED**:

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

## In review (NOT merged)

- **PR-08 — Production runtime product enrichment** (`commerce.read`).
  - Runtime implementation verified at: `efbddc52f4559513bf85b89632b5b1aa395f67a4`
    (base `main`, CI **green**). Subsequent commits on the PR, if any, are
    documentation-only cleanup — this SHA is the exact code head that was tested.
  - Internal `RuntimeProductCard/Page/ServiceArea` composer: one serviceability resolution
    per request, one batched inventory read per page (≤ `MAX_PAGE_SIZE=50`), frozen buyable
    rule, cross-location isolation proven by integration tests.
  - Branch verification: **842 tests green** (0 failures / 0 errors / 0 skipped).

## Blocked

- (none tracked)

## Tracked debt

- See [`docs/architecture/DEBT-REGISTER.md`](architecture/DEBT-REGISTER.md). Currently OPEN:
  **OPENAPI-INTERNAL-PROJECTION-DEBT** — the frozen `/v1` file's internal
  `ProductCardBaseProjection` schema has drifted from the implemented projection;
  **MUST FIX BEFORE PR-10** exposes any public endpoint.

## Next (ratified sequence)

1. **Merge PR-08** (on explicit approval).
2. **PR-09** — PDP / runtime detail composition (builds on the `enrichOne` seam).
3. **PR-10** — Public API / gateway / cache / observability / freshness gate. Must close
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
  Docker (MongoDB 7, Redis via Testcontainers), PR-08 branch at review head:
  **BUILD SUCCESS**, **842 tests, 0 failures / 0 errors / 0 skipped**, ~2:00 min.
