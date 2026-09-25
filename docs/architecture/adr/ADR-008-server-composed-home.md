# ADR-008: Home is server-composed using versioned blocks

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Home merchandising (ordering, visibility, banners, shelves) must change without an app release and must degrade gracefully on older clients.

## Decision
`GET /v1/home` returns a versioned `HomePage { schemaVersion, blocks[] }`. The backend controls which blocks appear, their order, visibility and copy. Clients **skip unknown block types**. Design tokens remain client-owned (see ADR-012).

## Alternatives considered
- Client-orchestrated Home — rejected (ties merchandising to app releases; no experimentation).

## Consequences
Server-driven merchandising; forward-compatible block model. (Home is a later phase; documented here for the frozen contract.)

## Compatibility / migration implications
Additive block types are backward compatible by the skip-unknown rule (ADR-011).
