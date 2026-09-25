# ADR-010: Public API error envelope

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Public clients need one predictable, safe error shape; no internals may leak.

## Decision
Flat envelope: `{ code, message, requestId, retryable, retryAfterSeconds?, details? }`. Frozen codes: `INVALID_REQUEST, INVALID_CURSOR, NOT_FOUND, RATE_LIMITED, SERVICE_UNAVAILABLE, INTERNAL`. `message` is generic; no stack traces or internal service names. `Retry-After` header accompanies 429/503.

## Alternatives considered
- Nested `{error:{…}}` CMS envelope — rejected for the consumer plane (Q4-c: separate contract).

## Consequences
The existing `/catalog/v1` flat consumer error `{code,message,request_id}` maps cleanly; `/v1` adds `retryable`/`retryAfterSeconds` (additive).

## Compatibility / migration implications
`/catalog/v1` unchanged. `/v1` is a superset.
