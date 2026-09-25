# ADR-009: Cursor pagination is the production listing standard

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
Product lists are large and change under live catalog/inventory; offset paging is unstable and expensive.

## Decision
All large listings use **opaque, signed cursors** (the existing catalog cursor codec is the implementation). No offset or total-count. `PagedResult<T> { items, nextCursor?, hasMore, resolvedReleaseId, serviceArea?, requestId }`. Clients never parse cursors; `hasMore = nextCursor != null`. Location change resets pagination.

## Alternatives considered
- Offset/limit + count — rejected (unstable, costly, leaks size).

## Consequences
Stable keyset paging; clients dedupe by stable id across pages; invalid/expired cursor resets to first page.

## Compatibility / migration implications
Already implemented in `/catalog/v1`; reused unchanged.
