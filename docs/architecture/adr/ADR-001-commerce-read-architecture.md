# ADR-001: Commerce read architecture

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
The mobile app is a full quick-commerce client but the only real backend is `catalog-service`, which serves a thin taxonomy/identity read surface. A production browse experience needs price, inventory, media and serviceability composed into one response per screen.

## Decision
Domain modules own their writes. A **Commerce Read** module maintains a composed, consumer-ready read model and serves reads through a `/v1` API. Clients make **one call per screen** and never fan out across domains. Location-specific state (stock/ETA) is enriched at request time, not persisted in the shared projection (see ADR-005, ADR-004).

## Alternatives considered
- Client fan-out to N services — rejected (p99 = slowest dependency, partial-failure logic on device, no web reuse).
- Request-time BFF synchronous aggregation on the hot path — rejected for the browse path (fan-out latency/partial failure).
- Full microservice split now — rejected (premature; see ADR on modular monolith / physical deployment).

## Consequences
Bounded p99, cacheable browse, web reuse, single client integration point. Requires a projection and read ports per domain.

## Compatibility / migration implications
Additive. Existing `/catalog/v1` is unchanged; `/v1` is a new composed contract (see ADR-003-1).
