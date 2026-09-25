# Tazzzo Backend — Engineering Status

Single source of truth for what is actually built and verified in `tazzzo-backend`.
Reflects **current reality only** — nothing is marked complete unless verified from existing code.

Last updated: 2026-09-25

---

## Current

- **catalog-service** — the only real service in this repository. Migrated into
  `services/catalog-service/` with full Git history preserved, and independently verified.
  - Status: **migrated / verified**
  - Language/runtime: **Java 21** (Temurin 21)
  - Framework: **Spring Boot 3.3.5**
  - Datastore: **MongoDB** (primary; event-sourced write path)
  - Cache/limiter: **Redis** (consumer rate limiter only)
  - Tests: **565 / 565 passing** (0 failures, 0 errors, 0 skipped) via JUnit 5 + Testcontainers
    (MongoDB 7 + Redis) — verified 2026-09-25.

## In Progress

- **PR-01 — Production commerce architecture & contract foundation.** Adds ratified ADRs
  (`docs/architecture/`), the frozen `/v1` OpenAPI contract + contract reference
  (`docs/api/v1/`), ArchUnit module-boundary tests, and backend CI. **Documentation/contracts/CI
  only — no Pricing/Inventory/Media/Serviceability/projection/KMP behavior is implemented, and
  `/catalog/v1` runtime semantics are unchanged.** Phase 3.1 is ratified; see
  [architecture index](architecture/README.md).

## Completed

- Repository restructuring: `tazzzo-backend` created; `catalog-service` imported under
  `services/catalog-service` (history-preserving subtree, 39 commits, root `9807372` → `5e4ef5a`).
- Full catalog test suite verified green on Java 21 (565/565).
- Backend housekeeping `.gitignore` and `CLAUDE.md` engineering rules added.

## Blocked

- (none tracked)

## Next

Services **not started** (no code exists yet — do not assume otherwise):

- auth-service
- customer-service
- inventory-service
- cart-service
- checkout-service
- order-service
- search-service
- notification-service

> Note: catalogue CMS / attribute-governance / offers logic currently lives **inside**
> `catalog-service`; any future extraction (e.g. a separate cms-service) is a design decision,
> not yet started.

## Last verification

- **2026-09-25** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 +
  Docker (MongoDB 7, Redis via Testcontainers): **BUILD SUCCESS**, 565/565 tests passing,
  execution time ~1:58. Migrated tree confirmed byte-identical to the original catalog repo.
