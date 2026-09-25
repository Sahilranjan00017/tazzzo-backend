# ADR-007: Mobile/web never fan out directly to multiple backend domains

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Client-side aggregation pushes latency, partial-failure handling and consistency onto the device and prevents web reuse.

## Decision
Clients call only the `/v1` Commerce Read plane (via the gateway). All cross-domain composition happens server-side.

## Alternatives considered
- Per-screen multi-service calls from the client — rejected.

## Consequences
Simpler, uniform clients; composition centralized and observable.

## Compatibility / migration implications
None; the app has no networking yet.
