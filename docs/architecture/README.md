# Tazzzo Backend — Architecture Index

Production commerce architecture, ratified in **Phase 3.1**. This index is the entry
point for the frozen decisions, contracts, and implementation sequence.

## Architecture Decision Records (ADRs)
| ADR | Decision |
|---|---|
| [001](adr/ADR-001-commerce-read-architecture.md) | Commerce read architecture (domain writes + composed read model) |
| [002](adr/ADR-002-money-integer-paise.md) | Money = integer paise (int64) |
| [003](adr/ADR-003-catalog-pricing-ownership.md) | Catalog does not own price; Pricing does |
| [003.1](adr/ADR-003-1-rp8-commerce-read-clarification.md) | RP-8 clarification — composed `/v1` may expose price/inventory |
| [004](adr/ADR-004-inventory-authority.md) | Inventory is authoritative for stock |
| [005](adr/ADR-005-browse-consistency.md) | Browse projections may be eventually consistent |
| [006](adr/ADR-006-checkout-revalidation.md) | Checkout revalidates authoritative price + inventory |
| [007](adr/ADR-007-no-client-domain-fanout.md) | Clients never fan out across domains |
| [008](adr/ADR-008-server-composed-home.md) | Home is server-composed versioned blocks |
| [009](adr/ADR-009-cursor-pagination.md) | Cursor pagination is the listing standard |
| [010](adr/ADR-010-public-error-envelope.md) | Public error envelope |
| [011](adr/ADR-011-api-compatibility.md) | API backward-compatibility rules |
| [012](adr/ADR-012-cms-presentation-boundary.md) | CMS controls merchandising, app owns visual system |
| [013](adr/ADR-013-backend-runtime-baseline.md) | Java 21 / Spring Boot 3.3.5 baseline |
| [014](adr/ADR-014-observability-request-id.md) | Observability / request-id |
| [015](adr/ADR-015-idempotent-writes.md) | Idempotency for writes |

## API contract (`/v1`, frozen — not implemented)
- Machine-readable: [`../api/v1/openapi.yaml`](../api/v1/openapi.yaml)
- Narrative (money / location / error / pagination / module boundaries): [`../api/v1/CONTRACTS.md`](../api/v1/CONTRACTS.md)
- Existing `/catalog/v1` + `/api/v1` remain unchanged: `../openapi.json`.

## Module boundaries (enforced by ArchUnit)
```
catalog   pricing   inventory   media   serviceability     (domain peers, no cross-deps)
                         │ (read ports)
                   commerce.read                            (composition)
                         │
                   commerce.api                             (/v1 controllers)
```
Rule: no domain depends on `commerce.read`; `commerce.read` depends only on domain read ports;
`commerce.api` depends only on `commerce.read`; no cycles. Test:
`services/catalog-service/src/test/java/com/tazzzo/arch/ModuleBoundaryTest.java`
(tolerant of not-yet-existing modules via `allowEmptyShould(true)`).

## Compatibility rules
Additive-only within `/v1`; clients ignore unknown fields and treat unknown enum values as safe
defaults; breaking changes ⇒ `/v2`. Existing `/catalog/v1` semantics are never changed silently.
See ADR-011.

## Implementation sequence (PR-01 → PR-11)
| PR | Scope |
|---|---|
| **PR-01** | **Architecture/contracts/CI foundation (this PR)** — ADRs, `/v1` OpenAPI, contracts, ArchUnit, CI |
| PR-02 | Money paise primitives + DTO / location-serviceability types |
| PR-03 | Pricing module (global-per-SKU; evolve offers ledger; paise + MRP) |
| PR-04 | Inventory module (per-location; atomic; NOT in base projection) |
| PR-05 | Media module (base projection media reference) |
| PR-06 | Serviceability module (PIN → serviceArea → fulfillment location) |
| PR-07 | ProductCardBaseProjection (identity + price + media; same-txn updates) |
| PR-08 | `/v1/categories/{id}/products` = base ⊕ runtime inventory/serviceability enrichment |
| PR-09 | `/v1/products/{id}` ProductDetail + enrichment |
| PR-10 | Gateway `/v1`, two-layer caching, ETag, rate-limit reuse, observability |
| PR-11 | KMP app networking (Ktor) + RemoteCatalogRepository behind a DEV flag |
