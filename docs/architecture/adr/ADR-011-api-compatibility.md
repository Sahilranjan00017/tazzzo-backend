# ADR-011: API backward compatibility rules

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
The app must survive additive backend evolution without breaking.

## Decision
Within `/v1`: **additive-only** (new optional fields, new enum values, new block types). Clients **ignore unknown fields** and **treat unknown enum values as a safe default** (e.g. unknown `stockState` ⇒ not-buyable). Breaking changes require a new path version (`/v2`) with a deprecation window. A minimum-supported-app-version may be enforced at the gateway.

## Alternatives considered
- Silent breaking changes / field removal in place — rejected.

## Consequences
CI enforces additive compatibility on protected contracts. Clients configure JSON parsing to ignore unknowns.

## Compatibility / migration implications
Governs all future contract edits.
