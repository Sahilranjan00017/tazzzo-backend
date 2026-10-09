# ADR-016: Backend runtime baseline = Java 21 / Spring Boot 4.1 (Jackson 2 retained)

- **Status:** Accepted · **Date:** 2026-10-09 · **Supersedes:** the framework version in [ADR-013](ADR-013-backend-runtime-baseline.md)
  (Java 21, Maven wrapper, MongoDB 7, Redis for rate limiting and the modular-monolith decision in ADR-013 stand unchanged)

## Context
ADR-013 pinned Spring Boot 3.3.5; the service later moved to 3.3.13, the final 3.3 release. Boot 3.3 left open-source
support on 2025-06-30, so security fixes now arrive only as hand-maintained BOM overrides (see
`docs/security/DEPENDENCY_SECURITY_REVIEW_2026-10.md`), which is not a sustainable posture. ADR-013 rejected Boot 4 because it
then meant an unproven Postgres scaffold; that reason no longer applies to this Mongo-native service.

Maven Central (checked 2026-10-09): `spring-boot-starter-parent` latest GA is **4.1.1**; 4.2 exists only as milestones
(4.2.0-M1, M2). 4.0.x is still published (4.0.8) but 4.1 is the newest supported line.

## Decision
1. Production baseline is **Java 21 + Spring Boot 4.1.1** (resolved: Spring Framework 7.0.9, Spring Data MongoDB 5.1.1, MongoDB driver 5.8.1,
   Lettuce 7.5.2, Tomcat 11.0.24, Micrometer 1.17.1, as managed by the Boot BOM). Boot 4.2 is not adopted until GA.
2. **Jackson 2 is kept for this step.** Boot 4 defaults to Jackson 3 (`tools.jackson`). Application code, DTO annotations
   (`@JsonUnwrapped`, `@JsonInclude`), filters and tests are written against Jackson 2, and the public JSON wire format is a frozen
   contract. The service therefore adds `spring-boot-jackson2` and sets `spring.http.converters.preferred-json-mapper=jackson2`.
   Migrating to Jackson 3 is a **separate follow-up change**, because it touches ~160 imports and every serializer-sensitive
   response and would otherwise make a platform upgrade impossible to review or bisect.
3. springdoc-openapi moves to **3.1.1** (the 2.x line does not support Boot 4 / Framework 7), configured with
   `springdoc.api-docs.version=openapi_3_0` so the published document stays OpenAPI 3.0.1.
4. Testcontainers moves to **2.0.5** as managed by the Boot BOM (artifacts renamed `testcontainers-mongodb`,
   `testcontainers-junit-jupiter`). `TestRestTemplate` now comes from `spring-boot-resttestclient`.
5. The hand-maintained security overrides for Tomcat and Netty are removed; the Boot 4.1.1 BOM manages newer versions
   (Tomcat 11.0.24). The Jackson 2 BOM is overridden to **2.21.7** (`jackson-2-bom.version`): the BOM's 2.21.5 regresses the
   number-parsing ReDoS guard (a 60 KB numeric string coerced into a `Double` request field took ~27 s;
   `AddressCoordinateParsingIT` caught it; 2.21.7 is fast again, 2.18.11 was the previous pin). Remove the override once the
   Boot BOM manages >= 2.21.7. The Jackson 3 line (3.1.5, not on the request path in this step) shows the same slowness in a
   standalone probe, so the Jackson 3 follow-up must pick a fixed 3.1.x before it switches the converters.
   `nimbus-jose-jwt`, `icu4j`, `archunit` and the AWS SDK BOM stay explicitly pinned and are unchanged.

## Alternatives considered
- **Stay on 3.3.13** — rejected: unsupported platform.
- **Boot 3.5.x as a stepping stone** — rejected as a deployed target: it is itself the end of the 3.x line, and the service has
  no Spring Security or JPA, so the Boot 4 delta is small enough to take directly.
- **Boot 4.0.x** — rejected: 4.1.1 is the newest supported GA line.
- **Jackson 3 in the same change** — rejected as the riskier split (see Decision 2).

## Consequences
- Configuration keys changed and are preserved by this change: `spring.data.mongodb.uri|database` is now
  `spring.mongodb.uri|database`. Environment variables (`MONGODB_URI`, `MONGODB_DATABASE`, ...) are unchanged, so deployment
  configuration needs no change.
- Behaviour preserved by small code changes: Tomcat 11 now throws `IllegalStateException` from `getParameterMap()` for an
  undecodable query parameter (Tomcat 10 dropped it silently); `RawQuerySyntax.bind` maps that to the existing 400
  `MALFORMED_REQUEST`, otherwise the audit read would have answered 409 `STATE_CONFLICT`. Spring Framework 7 renamed
  `HttpStatus.UNPROCESSABLE_ENTITY` to `UNPROCESSABLE_CONTENT` (same 422 on the wire); tests were updated, main code still
  uses the deprecated constant (cleanup deferred).
- Container image: Boot 4 removed `-Djarmode=layertools`; the Dockerfile uses `-Djarmode=tools extract --layers --launcher`,
  which yields the same four layers. Base image digest, uid 10001 and the empty environment are unchanged.
- Code changes: the two Redis auto-configurations excluded in `CatalogApplication` were renamed by Boot 4; Lettuce 7 removed
  `RedisURI#getPassword/#getUsername` so `ConsumerRateLimitConfig` reads credentials from the URI's credentials provider.
- Generated OpenAPI (`docs/openapi.json`): same paths, operations, parameters and responses. Intended deltas are (a)
  `operationId` suffixes renumbered (23 operations, e.g. `get_7` -> `get`), a function of springdoc's ordering; clients generated
  from operationId must be regenerated; (b) `ProductDetailDto` and `NodeDetailDto` schemas now list their `@JsonUnwrapped` card
  fields, which the runtime JSON always contained (the document previously under-described the flat response).
- Jackson 2 is deprecated in Boot 4 and Spring Framework 7; the follow-up Jackson 3 migration must be scheduled before the
  `spring-boot-jackson2` module is removed (planned for Boot 4.3 / 5).

## Compatibility / migration implications
HTTP routes, request/response shapes and Actuator exposure are unchanged (verified by the unchanged contract tests and
`ApiContractParityIT`). Operations: roll out like any release; no data migration. Rollback is a revert of this change.
