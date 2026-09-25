# ADR-013: Backend runtime baseline = Java 21 / Spring Boot 3.3.5

- **Status:** Accepted · **Date:** 2026-09-25 · Ratified in Phase 3.1

## Context
A single, consistent backend stack is required. The catalog service is Java 21 / Boot 3.3.5 on MongoDB 7 + Redis; the abandoned stories scaffold was Boot 4 / Postgres.

## Decision
Production backend baseline is **Java 21**, **Spring Boot 3.3.5**, Maven wrapper, MongoDB 7 (replica set), Redis for rate limiting. New commerce modules live in the same deployable as catalog (modular monolith).

## Alternatives considered
- Spring Boot 4 (unproven scaffold) — rejected.
- Postgres/JPA — rejected (Catalog is Mongo-native event-sourced).

## Consequences
Consistent build/test toolchain; Testcontainers-based integration tests.

## Compatibility / migration implications
No change to the existing service; new modules adopt the same baseline.
