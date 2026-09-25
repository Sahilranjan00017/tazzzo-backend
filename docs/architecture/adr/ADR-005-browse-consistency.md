# ADR-005: Browse projections may be eventually consistent

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Read models trade absolute freshness for latency and cacheability.

## Decision
The persisted base projection MAY lag its write sources within a bounded TTL. Responses echo `resolvedReleaseId` and expose freshness metadata. Purchase-critical truth is never taken from the projection (see ADR-006).

## Alternatives considered
- Strong-consistency reads on the hot path — rejected (latency, coupling).

## Consequences
Browse tolerates small staleness; the commit path revalidates.

## Compatibility / migration implications
None for existing contracts.
