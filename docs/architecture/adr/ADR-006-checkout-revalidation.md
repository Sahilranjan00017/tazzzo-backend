# ADR-006: Checkout revalidates authoritative price + inventory

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Browse projections may be stale; purchase must be correct.

## Decision
Cart-validate and checkout re-read authoritative Pricing and Inventory synchronously. The browse projection is never trusted for purchase commitment. Price/stock mismatch surfaces as `409 CONFLICT` for user re-confirmation.

## Alternatives considered
- Trusting the projection at commit — rejected (oversell, price drift).

## Consequences
Defines the future checkout contract. Not implemented in the browse phases.

## Compatibility / migration implications
Forward-looking; no current impact.
