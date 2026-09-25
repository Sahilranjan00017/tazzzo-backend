# ADR-015: Idempotency requirements for writes

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Retries must never create duplicate side effects (double orders/payments).

## Decision
All state-changing writes (cart mutation, checkout, order, payment, coin credit) carry an **idempotency key** and are server-deduplicated. **GET is safely retryable; writes are never auto-retried** by clients/gateway unless idempotency-keyed and deduplicated. Duplicate-key replays return the original result.

## Alternatives considered
- Naive client retries on writes — rejected (duplicate side effects).

## Consequences
Constrains the future checkout/order/payment design. The browse phases are read-only and unaffected.

## Compatibility / migration implications
Forward-looking; no current runtime impact.
