# ADR-003.1: RP-8 clarification — composed Commerce Read may expose price/inventory

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
RP-8 states the raw Catalog consumer response is not a commerce card (no price/inventory). Phase 2/3 introduced a composed `/v1` ProductCard that includes price/stock/media/serviceability. This appeared to conflict with RP-8.

## Decision
**RP-8 remains valid for the raw Catalog domain.** `/catalog/v1` semantics are unchanged and continue to exclude price/inventory. The **new `/v1` Commerce Read API is a composed consumer contract** that MAY include price, inventory, media and serviceability **sourced from their authoritative domains** (Pricing, Inventory, Media, Serviceability) — Catalog does not become the owner of commerce state. This is a clarification, not a reversal of RP-8.

## Alternatives considered
- Making Catalog own commerce state — rejected (violates ADR-003 single-source-of-truth).
- Reversing RP-8 on `/catalog/v1` — rejected (breaks the ratified raw-catalog contract).

## Consequences
Two contracts coexist: raw `/catalog/v1` (identity) and composed `/v1` (commerce). Composition happens in the Commerce Read module via domain read ports.

## Compatibility / migration implications
Fully additive. `/catalog/v1` untouched.
