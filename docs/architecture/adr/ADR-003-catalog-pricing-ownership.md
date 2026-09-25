# ADR-003: Catalog does not own authoritative price; Pricing does

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
A single source of truth per fact is required. Price must not live independently in Catalog and Pricing.

## Decision
**Pricing** is the authoritative owner of `sellingPricePaise` and `mrpPaise`. **Catalog** owns product/taxonomy identity only and carries no consumer price. This matches the existing RP-8 decision.

## Alternatives considered
- Price inside Catalog documents — rejected (dual source of truth, coupling).

## Consequences
Pricing module owns the price write model (evolved from the existing `offers_current`/`price_events` ledger). Catalog stays identity-only.

## Compatibility / migration implications
No change to Catalog contracts. Pricing evolves the existing tested ledger additively (later PR).
