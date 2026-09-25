# ADR-014: Observability and request-id requirements

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Production requires traceable, measurable requests without leaking secrets/PII.

## Decision
Every request carries a propagated `requestId` (server-generated if absent; the existing `RequestIdFilter` is the basis). Structured logs, RED metrics (rate/errors/duration) with p50/p95/p99 per route, distributed traces, dependency latency, cache-hit rate, rate-limit events, and data-inconsistency counters (price-missing, stock-unknown, projection-stale). **Never log** passwords, OTP, tokens, or sensitive PII. Telemetry exporter choice is a deployment decision (open).

## Alternatives considered
- Ad-hoc logging without correlation — rejected.

## Consequences
Read/response paths must emit requestId and metrics hooks.

## Compatibility / migration implications
The consumer plane already logs reason+request_id with generic messages; extended additively.
