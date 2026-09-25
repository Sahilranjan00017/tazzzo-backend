# ADR-002: Money representation = integer paise (int64)

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Money must be exact. The app currently uses `Int` **rupees**, which cannot represent paise and is unsafe for discounts/coins.

## Decision
All monetary amounts on production contracts are **integer paise** as **int64** (`sellingPricePaise`, `mrpPaise`, `discountAmountPaise`). Currency is **INR** for initial production (implicit). No floating-point money anywhere; amounts are never transported as decimals or floats.

## Alternatives considered
- Float/double — rejected (precision).
- Decimal-as-string — rejected (parsing burden, still ambiguous unit).
- Int rupees (current app) — rejected (loses paise).

## Consequences
Exact arithmetic. The app must migrate off `Int` rupees (breaking change, handled in a later PR).

## Compatibility / migration implications
New fields are explicitly `*_paise`. The legacy `price` int is NOT reinterpreted (see ADR-003 and migration notes). `/v1` exposes only validated paise fields.
