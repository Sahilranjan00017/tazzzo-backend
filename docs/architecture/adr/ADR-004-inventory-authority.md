# ADR-004: Inventory is authoritative for stock

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Stock is per fulfillment location and changes frequently; overselling must be prevented.

## Decision
The **Inventory** module is the sole source of truth for `onHand`, `reserved`, `available`, `stockState`, and inventory-derived purchase caps, keyed by `(skuId, fulfillmentLocationId)`. Stock is resolved at request time for the server-selected fulfillment location and is **not** persisted in the shared base projection.

## Alternatives considered
- Stock inside Catalog/Offers — rejected (wrong owner, write amplification).
- Stock baked into the global projection — rejected (per-location; would require N×M projection rows and leak across locations).

## Consequences
Inventory owns atomic, optimistic-concurrency updates. ProductCard stock is a runtime enrichment.

## Compatibility / migration implications
New collection, additive. No change to existing contracts.
