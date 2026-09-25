# tazzzo-catalog-service

Spring Boot implementation of the Tazzzo **Implementation Contract V1**
(see `../Tazzzo Research/Tazzzo_Implementation_Contract_V1.md` and `docs/SPEC.md`).

What exists and is proven by tests (24/24 green against real MongoDB 7 via Testcontainers):

- `schema/SchemaBootstrap` — idempotent creation of the core collections, the **executed**
  products `$jsonSchema` validator (oneOf shape rule, closed key sets), and indexes.
- `repo/WritePath` — the single write path. C-3: event appended before state, same session.
  C-4: an `EventPayload` parameter is required at compile time on every mutation.
- `tx/` — T1 mint, T2 merge (transactional outbox + leased, idempotent finalizer = K-1),
  T3 claim publish (evidence fence), T4 classify, T5 GTIN bind, T7 bundle write
  (component liveness in-txn, Law-4 fail-closed on post-merge bundle collapse), T6 minimal
  release state machine. Contract exceptions: CasConflict, IdentityCollision, EvidenceGate,
  BundleComponent.
- Tests: `ValidatorContractIT` (executed-attack port incl. AT-1 zero-drift + the two
  accepted-bypass proofs), `TransactionAtomicityIT` (atomic absence + R-1/R-2 mint race),
  `MergeCrashIT` (K-1 crash → resume → idempotent rerun), `PublishFenceIT` (gate, real
  retraction path, two-session WriteConflict).

## Running tests
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) DOCKER_API_VERSION=1.44 ./mvnw test
```
Machine prerequisites (already configured on this machine):
- Docker Desktop running; `~/.testcontainers.properties` points at the Desktop socket and
  disables ryuk.
- `DOCKER_API_VERSION=1.44` (also persisted in `~/.docker-java.properties`): Docker engine
  29.x sets min API 1.40; the docker-java client otherwise negotiates an older version and
  gets HTTP 400.
- JDK 21 (Temurin). The machine default JDK 27-EA is not supported by the test toolchain.

## Hardening slices (second wave - all executed)
- **K-2** RollupStallIT: stalled rollup => purge deletes nothing; purge deletes ONLY events
  durably marked rolled (post-review protocol - also closes the watermark race and makes
  rollup idempotent).
- **K-3** TaintCrashIT: retraction enqueues the cascade in the same txn (A2-triple-prime);
  worker death mid-scan leaves a durable checkpoint; lease-expiry re-claim resumes
  exactly-once (deterministic work-item ids).
- **G-4 executed** ValidatorRegenIT: unregistered language rejected with reason token
  (T-RULE-1) -> register in system_config -> ValidatorGenerator.regenerate() (the T6
  collMod step) -> accepted; a still-unregistered language stays rejected (Law 4b).
- **L-1 slice** ScaleIT: 100,000 validated products inserted in ~1.8s; boring-SKU rule
  holds at scale; category query in single-digit ms with the WINNING plan asserted IXSCAN.
- New services: OffersService (ledger-before-state, one txn), RollupService, TaintService;
  11 more collections in SchemaBootstrap with unique indexes.

Accepted/documented deviations: one ledger event per WritePath call (per-write event
granularity); taint re-enqueue after hypothetical evidence reactivation needs a status
reset (no reactivation path exists in V1).

## Still ahead (next slices)
R-1 restore drill tooling (backup manifest triple) - price_events native time-series
migration decision - campaigns/crosswalk services - role/credential provisioning on Atlas -
release-pipeline CI integration (validator generation implemented; release wiring is not).
