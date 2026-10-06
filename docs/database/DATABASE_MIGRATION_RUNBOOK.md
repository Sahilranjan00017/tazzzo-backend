# DATABASE_MIGRATION_RUNBOOK — tazzzo-backend (DB-3)

How database evolution works, how to run it, and what is forbidden. Implements the approved ruling **R5**
(no blind mutable/destructive evolution on every instance) and **R3** (reference data is versioned durable
state; no restart-time rewrite).

Nothing in this document has been executed against any staging or production database. Execution against
staging belongs to the later environment-verification phase.

## 1. Architecture

```
application start ──► MigrationStartupRunner ──► mode?
                         VERIFY (default)      read-only: history says required migrations are APPLIED? else refuse to start
                         DRY_RUN / APPLY       job modes (one-shot process, exit code)
                         APPLY_ON_STARTUP      local/test/dev only
                         LEGACY                the flag-driven bootstrap/seed; local/test/dev only (test suites); it does not create the migration-owned indexes

MigrationRunner ── TargetGuard ── MigrationLock ── MigrationHistory ── Migration[] (Migrations.defaults)
```

| Component | Role |
|---|---|
| `Migration` | one explicit, versioned, repeatable unit: id, kind, `preflight` (read-only), `apply` (idempotent), `validate`, `definition` (→ SHA-256 checksum) |
| `MigrationRunner` | `dryRun` (read-only), `apply` (locked, recorded), `verify` (read-only, used at startup) |
| `MigrationHistory` | collection `schema_migrations`, one document per migration id, fenced writes |
| `MigrationLock` | collection `schema_migration_lock`, single-runner lease with fencing token |
| `TargetGuard` | refuses ambiguous or disallowed targets before anything is mutated; the self-serve `local`/`test`/`dev` treatment applies only to a loopback-only datastore (staging runbook §6.2) |
| `BaselineV0001Contract` | the FROZEN collections and indexes of released V0001: its checksum input never follows the live `SchemaBootstrap`/`IndexCatalog`; new schema is a new migration (V0008+) |
| `IndexSpec` / `IndexCatalog` | declarative index definitions; classify live state as ABSENT / EXACT / SAME_KEYS_OTHER_NAME / CONFLICT |
| Generic migrations | `CreateIndexMigration`, `DropIndexMigration`, `ReplaceIndexMigration`, `ValidatorMigration` |

**Known limit — seed contents are not part of V0003's checksum (NOTE, future hardening).** V0003's recorded definition names the seed file and its counts
(460 nodes, 25 aliases, 110 definitions, 48 schemas) but not a hash of the file's contents, so an edit to `taxonomy_v0_9_0_seed.json` that keeps the counts would not change the checksum.
The only other guards are the count assertions in `TaxonomyFoundationIT` and the frozen-release discipline of taxonomy v0.9.0. Hashing the content would change the identity of an already-applied
migration, so it is deliberately NOT done here; a content hash belongs in a new seed migration (or the seed pack's own versioning) when the seed is next evolved. V0001, by contrast, now has an immutable
checksum input (`BaselineV0001Contract`, pinned by `BaselineFrozenContractTest`).

Code: `services/catalog-service/src/main/java/com/tazzzo/catalog/migration/`. The two bookkeeping collections
are created lazily by the runner on `APPLY`; they are not in `SchemaBootstrap.COLLECTIONS`, and `VERIFY`,
`DRY_RUN` and a refused run never create them.

## 2. Three kinds of database activity (R5)

| Category | What it is | Where it may happen |
|---|---|---|
| **A. Runtime verification** | read-only / idempotent checks: is every required migration recorded as applied with a matching checksum? | in the application at startup (`VERIFY`, the default). Needs read privileges only. A mismatch **fails closed**: the application refuses to start. |
| **B. Migration mutation** | creating collections/indexes, dropping/replacing indexes, `collMod`, any data rewrite | **only** in the controlled migration run (`APPLY`: locked, recorded, one runner) — never blindly on every instance. `APPLY_ON_STARTUP` exists for local/test/dev only. |
| **C. Reference initialization** | insert-if-absent creation of known initial reference data (the taxonomy seed) | as migration `V0003`. Never as a restart side effect. It never replaces or rewrites an existing document. |

`application.yml` no longer enables startup mutation: `tazzzo.migration.mode` defaults to `VERIFY`, and the
legacy flags `tazzzo.schema.bootstrap-on-startup` / `load-taxonomy-seed` default to `false` and are honoured
only in mode `LEGACY` (local/test/dev; the test suites use it via `src/test/resources/application.properties`).

> **What `VERIFY` checks:** it trusts the **history** (each required migration `APPLIED` with a matching checksum, none `FAILED`/`BLOCKED`/`APPLYING`); it does not re-inspect live indexes — drift detection is a later phase (DB-7). `V0005` and `V0006` are required migrations: a duplicate-blocked unique index leaves them unrecorded, so the application refuses to start until the duplicates are resolved and the migration has run. This is intended.
>
> **Deployment impact (action for the deployment pipeline owner):** an application deployed with the default
> `VERIFY` mode **refuses to start** against a database that has not been migrated. The pipeline must run the
> migration job (dry-run, review, apply) before rolling out the application. An existing database created by
> the legacy bootstrap needs one `APPLY` run that **adopts** it (§12) before the first `VERIFY` start.

## 3. Modes and configuration

Prefix `tazzzo.migration` (env vars in the second column). None of these values is a secret.

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `mode` | `TAZZZO_MIGRATION_MODE` | `VERIFY` | `VERIFY`, `DRY_RUN`, `APPLY`, `APPLY_ON_STARTUP`, `LEGACY` |
| `environment` | `TAZZZO_MIGRATION_ENVIRONMENT` | *(empty)* | `local`/`test`/`dev`/`staging`/`production`. **Required for any mutation.** |
| `confirm-database` | `TAZZZO_MIGRATION_CONFIRM_DATABASE` | *(empty)* | two-key confirmation for `APPLY` outside local/test/dev |
| `confirm-environment` | `TAZZZO_MIGRATION_CONFIRM_ENVIRONMENT` | *(empty)* | two-key confirmation (exact environment) |
| `operator` | `TAZZZO_MIGRATION_OPERATOR` | OS user | recorded in the history (use the deployment job id) |
| `build-version` | `TAZZZO_BUILD_VERSION` | `unknown` | recorded in the history |
| `lock-lease-seconds` | — | `300` | lease length; the runner renews it every third of the lease |
| `lock-wait-seconds` | — | `0` | how long to wait for a held lock (`0` = fail fast, outcome `LOCK_HELD`). `APPLY_ON_STARTUP` always waits at least 120 s so several local instances starting together do not crash |
| `exit-after-run` | `TAZZZO_MIGRATION_EXIT_AFTER_RUN` | `false` | job modes: exit the process with the run's exit code. **Use `true` for a one-shot job.** With `false`, a *failed* job always stops the application, but a *successful* `DRY_RUN`/`APPLY` carries on as a normal serving application **without** a `VERIFY` check |
| `verify-failure` | — | `FAIL` | `FAIL` (refuse to start) or `WARN` (log and continue) |
| `approved-data-migrations` | — | *(none)* | ids of DATA migrations explicitly approved for this run |
| `enabled-migrations` | — | *(none)* | ids of registered-but-disabled migrations to enable for this run |

## 4. Migration lifecycle

For every selected migration, in id order (`apply`):

1. **History check** — recorded `APPLIED`: verify the checksum (a mismatch stops the run, outcome `CHECKSUM_MISMATCH`: an applied migration was edited; add a new migration instead), else skip.
2. **Preflight** (read-only) → `BLOCKED` / `ALREADY_SATISFIED` / `READY`.
   - `BLOCKED`: record `BLOCKED` with the blockers, **stop the run** (later migrations are `NOT_RUN`), mutate nothing.
   - `ALREADY_SATISFIED`: record `APPLIED` with `adopted=true`; no change. (An approval-gated DATA migration is adopted without approval because nothing is changed.)
   - `READY`: a DATA migration without approval is reported `PENDING_APPROVAL` and not run.
3. **Apply** — record `APPLYING` (attempt counted), run `apply` (idempotent, re-inspects state), then **post-validate**.
   - Exception or validation problem: record `FAILED` with a sanitized error, stop the run.
   - Lock lost: stop immediately, record nothing as applied (§6).
4. **Record** `APPLIED` (note, duration, optional rollback info).

`FAILED` and `BLOCKED` are retried by simply running again (§10). A crash mid-apply leaves `APPLYING`; the next
run re-runs preflight and apply, which are idempotent.

## 5. Versioning and the registry

Ids sort lexicographically (`V0001…`); append only. The checksum of each applied migration is stored; editing an
applied migration is detected, never silently ignored.

| Id | Kind | Default | What it does | Blocks when |
|---|---|---|---|---|
| `V0001__baseline_schema` | SCHEMA | on | all collections + the 48 baseline indexes; adopts a legacy-bootstrapped database; drops the exact superseded products prefix index after PAG-2 exists | a baseline index name is taken by a different definition, the same keys exist with different options, or **duplicates exist under a baseline unique index that is missing** (reported with a sample, never repaired) |
| `V0002__products_vertical_id_cursor_index` | SCHEMA | on | `products {classification.vertical_id, _id}` (§9) | conflicting definition (§9 D) |
| `V0003__taxonomy_seed_0_9_0` | REFERENCE_INIT | on | insert-if-absent of the frozen taxonomy seed | never (insert-if-absent) |
| `V0004__seed_schemas_pack_fields_not_required` | DATA | on, **needs approval to change data** | `pack_size`/`pack_unit` `required=false` on seed schemas, **version 1 only** | never; adopted when nothing to change |
| `V0005__evidence_links_unique_link` | SCHEMA | on | unique `evidence_links (evidence_id, product_id, link_type)` | duplicates exist (§10), or conflicting definition |
| `V0006__taxonomy_nodes_unique_active_sibling_name` | SCHEMA | on | partial unique `taxonomy_nodes (parent_id, name)` where `status=active` | active-sibling duplicates exist, or conflicting definition |
| `V0007__audit_read_partial_indexes` | SCHEMA | on | nine partial indexes `audit_read_recent`/`_actor`/`_request` on `product_events`, `node_events`, `domain_events` (predicate `actor` is an object) for the admin audit-read API (PR #49) | conflicting definition on the same keys; none are unique, so no duplicate preflight |
| `V0014__notification_outbox_indexes` | SCHEMA | on | `notification_outbox` due scan `(status, next_attempt_at, _id)`, erasure lookup `(customer_id)` and TTL `expire_at` (0 s: rows carry created + 7 days); creates the collection on a migrated database (it is NOT in the frozen `V0001` baseline). Numbered V0014 because V0008–V0013 are taken by unmerged branches; renumber only if merge order demands it | conflicting definition on the same keys; none unique, so no duplicate preflight |
| `V0013__content_blocks_index` | SCHEMA | on | `content_blocks (placement, status, sort, _id)` for the CMS home read and admin list (PR-Q); creates `content_blocks` on a migrated database (the app config lives in the baseline `system_config` collection, so nothing else is needed) | conflicting definition on the same keys; not unique, so no duplicate preflight |
| `V0011__support_case_indexes` | SCHEMA | on | the three `support_cases` indexes (by customer, by status, overall; newest-updated first) for the support APIs (PR-O); creates the collection on a migrated database | conflicting definition on the same keys; none unique, so no duplicate preflight |
| `V0009__product_card_search_tokens_index` | SCHEMA | on | `product_card_base (search_tokens, sku_id)` multikey index for the public product search `GET /v1/search` (PR-G); the projector writes `search_tokens` and backfills older rows on their next rebuild | conflicting definition on the same keys; not unique, so no duplicate preflight |
| `V0008__delivery_slot_indexes` | SCHEMA | on | `delivery_slot_windows (service_area_id)` by-area lookup and `delivery_slot_usage` TTL on `expire_at` (0 s: purged a week after the slot date); creates both collections on a migrated database (they are NOT in the frozen V0001 baseline) for the delivery-slot admin/customer APIs (PR-E) | conflicting definition on the same keys; neither is unique, so no duplicate preflight |
| `V0010__orders_by_customer_recent_index` | SCHEMA | on | `orders (customerId, createdAt desc, _id desc)` for the customer order history `GET /v1/customer/orders` (PR-M); the existing unique `(customerId, quoteId)` index cannot serve the newest-first sort | conflicting definition on the same keys; not unique, so no duplicate preflight |
| `V0012__orders_staff_queue_indexes` | SCHEMA | on | `orders (status, createdAt desc, _id desc)` and `orders (createdAt desc, _id desc)` for the staff order queue (PR-M2) | conflicting definition on the same keys; not unique, so no duplicate preflight |
| `V0101__drop_unused_session_by_customer_index` | SCHEMA | **off** | drops `customer_sessions.session_by_customer` | live index is not the exact reviewed definition |
| `V0102__drop_unused_canonical_keys_product_id_index` | SCHEMA | **off** | drops `canonical_keys (product_id)` | same |

`IndexContractIT` pins the resulting index set: 51 indexes (48 baseline + 3 migration-managed), the four TTL
indexes, and that the executable `IndexCatalog` equals an independent oracle and what `bootstrap` creates.

## 6. Single runner: the lock

Collection `schema_migration_lock`, one document `_id: "catalog-service-migrations"`.

- **Atomic acquire** — a single compare-and-set `findOneAndUpdate` that matches only an expired or own lease, so MongoDB serialises concurrent attempts; exactly one wins. (The lock document is ensured separately because MongoDB forbids `$expr` in an upsert filter — found by test.)
- **Server time** — expiry is evaluated with the database's `$$NOW`, so clock skew between instances cannot produce two holders.
- **Fencing token** — `fence` increases on every acquisition; every history write carries it and is rejected if a newer holder wrote the record (`LockLostException`). A stalled process cannot rewrite history.
- **Heartbeat** — the lease is renewed every third of its length. If renewal fails the runner stops and records **nothing** as applied (outcome `LOCK_LOST`).
- **Release** — always in `finally`; a crashed holder is superseded when its lease expires (default 5 minutes).
- **Held by someone else** — wait `lock-wait-seconds`, else outcome `LOCK_HELD` (exit code 4); nothing is changed.

Fencing protects a record a newer holder has already written; in addition a stalled holder detects the lost lease (heartbeat) and stops before it records success. Committed tests prove: 8 concurrent runners apply a migration exactly once; a second runner is refused while the lock is held; release after failure; expired-lease takeover with a higher fence; a live lease cannot be taken; losing the lock mid-run records nothing as applied; a stale writer cannot overwrite newer history. A **one-off, uncommitted, local** mutation check (lock always acquirable; unfenced history; a dry run that creates a collection) failed the intended tests; that check is not reproduced by committed tests.

## 7. History: `schema_migrations`

One document per migration id: `_id`, `description`, `kind`, `checksum`, `status` (`APPLYING` / `APPLIED` / `FAILED` / `BLOCKED`), `attempts`, `startedAt`, `finishedAt`, `appliedAt`, `durationMs`, `buildVersion`, `environment`, `database`, `operator`, `runId`, `fence`, `adopted`, `note`, `error`, `blockers`, `rollbackInfo`, `updatedAt`.

Errors and blockers pass through `MigrationSanitizer` before being stored or logged: connection strings, `user:pass@`, and `password/token/secret/apikey=` values are redacted and the text is capped at 500 characters. Nothing recorded contains a connection string or credential.

## 8. Dry run and environment safety

**Dry run** (`mode=DRY_RUN`, or `MigrationRunner.dryRun`) is strictly read-only: no lock, no history collection, no collection of any kind is created. It reports, per migration: `ALREADY_APPLIED`, `WOULD_APPLY` (with the intended operations), `WOULD_ADOPT`, `BLOCKED` (with blockers), `PENDING_APPROVAL`, `CHECKSUM_MISMATCH`; plus the target (`environment`, `database`, `hosts`, `operator`, `build`) — never a connection string. Exit codes: `0` OK, `2` BLOCKED, `3` FAILED, `4` LOCK_HELD, `5` LOCK_LOST, `6` CHECKSUM_MISMATCH, `7` TARGET_REFUSED, `8` INVALID_SELECTION (an unknown id in `enabled-migrations`/`approved-data-migrations`: never silently ignored; nothing is run). A dry run evaluates **each migration independently against the current state** (it cannot simulate earlier migrations having run), whereas `apply` stops at the first problem; so a dry run can show `WOULD_APPLY` for a step that `apply` would report `NOT_RUN`. When several problems exist the dry-run outcome is `CHECKSUM_MISMATCH` over `BLOCKED`. A migration caught mid-flight by a lost lock is reported `INTERRUPTED` (left `APPLYING`).

**Target guard** — before any mutation, in every mode:

| Rule | Effect |
|---|---|
| environment not set, or not one of `local/test/dev/staging/production` | refused (`TARGET_REFUSED`), nothing created |
| database name unknown | refused |
| `APPLY_ON_STARTUP` or `LEGACY` mutation in `staging`/`production` | refused — use the job |
| `APPLY` in `staging`/`production` | two-key confirmation: `confirm-database` **and** `confirm-environment` must equal the actual target exactly |

The target is derived from the live client: database name from the connection, hosts from the cluster settings (host:port only).

## 9. Index migration rules

Every index is described by an `IndexSpec` (collection, name, ordered keys with direction, unique, sparse, partial filter, TTL). A live database is classified for each spec:

| State | Meaning | `CreateIndexMigration` does |
|---|---|---|
| **A. ABSENT** | no index with this name or key pattern (or no collection) | creates it |
| **B. EXACT** | same name, same definition | nothing; **adopted** |
| **C. SAME_KEYS_OTHER_NAME** | same keys and options under a different name | nothing; **adopted** (the need is met; the name is cosmetic; a rename is a separate explicit migration). MongoDB would throw `IndexOptionsConflict` if created by name — the migration never attempts it |
| **D. CONFLICT** | the name exists with a different definition, **or** the same keys exist with different options | **BLOCKED**. Nothing is dropped, altered or created. An operator decides. |

**Key order and direction are part of an index's identity**: `{a:1,b:1}` and `{b:1,a:1}` (or `{a:1}` and `{a:-1}`) are different indexes serving different queries, so a reversed index is never adopted and never satisfies validation (tested). Two *different* partial filters on the same keys are two legitimate indexes (as for `otp_one_delivering_per_phone` / `otp_one_active_per_phone`), not a conflict.

### 9.1 Deployment preflight for `products (classification.vertical_id, _id)` — **required before any rollout**

The DB-2 index `product_vertical_id_cursor` was created by startup bootstrap, where an existing index with the same keys under another name would abort every instance with `IndexOptionsConflict`. It is now migration `V0002`, which handles each case deterministically. Before rolling out to **any** persistent environment, inspect `products`:

```javascript
// mongosh, read-only
db.products.getIndexes().filter(i => JSON.stringify(i.key) === '{"classification.vertical_id":1,"_id":1}')
```

| Case | Live state | Behaviour |
|---|---|---|
| **A. absent** | no such index | `WOULD_APPLY` → created by `APPLY` |
| **B. exact** | `product_vertical_id_cursor`, non-unique, no partial/sparse/TTL | `WOULD_ADOPT` → recorded, no change |
| **C. same keys, other name** | e.g. `hand_made_vertical_cursor`, same options | `WOULD_ADOPT` → recorded, no change, **no `IndexOptionsConflict`** |
| **D. conflicting options** | `product_vertical_id_cursor` with different options (e.g. sparse), **or** the same keys under another name with different options (e.g. unique) | `BLOCKED` → nothing changed; the operator decides (typically drop the stray index deliberately, then re-run) |

**Build time and load must be evaluated before production rollout.** Creating an index on a large `products` collection is I/O-heavy and its duration is unknown (UNVERIFIED): measure on staging data of production size first. MongoDB 7 builds online (short exclusive locks at the start and end of the build) but adds load; run the migration job in a quiet window. Because it now runs in the migration job — not inside the startup runner of every instance — an index build no longer delays application readiness.

### 9.2 Unique indexes and the duplicate preflight

A unique index is created only if a read-only duplicate query returns nothing. The check **reports** (up to 20 duplicate groups) and **never deletes, merges or edits** business data.

```javascript
// V0005 evidence_links — must return no rows
db.evidence_links.aggregate([{$group:{_id:{evidence_id:"$evidence_id",product_id:"$product_id",link_type:"$link_type"},n:{$sum:1}}},{$match:{n:{$gt:1}}}])
// V0006 taxonomy_nodes — must return no rows (only ACTIVE nodes matter)
db.taxonomy_nodes.aggregate([{$match:{status:"active"}},{$group:{_id:{parent_id:"$parent_id",name:"$name"},n:{$sum:1}}},{$match:{n:{$gt:1}}}])
```

If duplicates exist the migration records `BLOCKED` with a sample of the keys, the index is not created, and the run stops safely. Remediation is a business decision made outside the migration; the same migration then proceeds on the next run. The frozen taxonomy seed contains no duplicate active sibling names (checked: 0 of 460), but live data is UNVERIFIED.

### 9.3 Drop and replace

- `DropIndexMigration` drops **only** the exact reviewed definition: a same-keys index under another name, or any different definition, **blocks**. Roll-forward: re-create from the same spec (recorded in `rollbackInfo`).
- `ReplaceIndexMigration` (a redefinition or a rename) chooses its order from one rule — **can the old and new definitions coexist?** MongoDB forbids two indexes with the same ordered keys and options under different names, but allows two partial indexes whose filters differ.
  - **Can coexist** (a different key pattern, or two different partial filters): **create-before-drop**, so there is no window without coverage.
  - **Cannot coexist** (the same ordered keys: a pure rename, or a change of options such as adding `unique`; or simply the **same index name**, which a collection can hold only once even for different keys): **drop-then-create**, with a short window without the index — schedule it in a quiet period under the lock. It is **resumable**: if the run dies between the drop and the create, the retry sees the source gone and the target absent and just creates the target (it does not block forever).
  - The old index is dropped only if it is exactly the reviewed definition, **and only after the target has been checked as if the source were already gone**: an equivalent or conflicting index under a *third* name blocks *before* anything is dropped (otherwise the source would be dropped and the create would then fail).
  - **Every drop-first replacement whose target is unique must carry a duplicate preflight** (the constructor rejects one without it). Dropping the source and then failing to build a unique target would leave the collection without the index, and that can happen not only when the source was non-unique but also for a narrower unique key, a wider partial filter or a dropped sparse flag. Duplicates only ever block and are never repaired. A *coexisting* replacement creates before it drops, so a failed create keeps the source and needs no check.
  - **A replacement does not adopt an equivalent index under another name** (unlike a plain create): the reviewed name must exist afterwards, so it blocks instead.
  - **`apply` re-runs the preflight's guards immediately before any destructive step** (one source of truth), so a state that changed since the preflight — a third-name index, new duplicates — makes it refuse instead of dropping. This narrows the window between the check and the drop; it cannot remove it without a transaction around index DDL, which MongoDB does not offer, and whatever remains is resumable and never silently wrong.
- Neither drop nor replace is used by a default migration; drop candidates are registered **disabled**.

## 10. Failure handling

| Outcome | Meaning | What to do |
|---|---|---|
| `BLOCKED` | a preflight found a conflict or duplicates; **nothing mutated** | read the blockers in the report/history; resolve deliberately; re-run |
| `FAILED` | `apply` threw, **a preflight threw (for example a timeout)**, or post-validation failed; recorded with a sanitized error | fix the cause; re-run (the migration is retried; `attempts` increments). `apply` is idempotent, so a partial change is completed or re-checked |
| `CHECKSUM_MISMATCH` | an applied migration's definition was edited | revert the edit; add a **new** migration instead |
| `LOCK_HELD` | another run holds the lease | wait, or raise `lock-wait-seconds`; never delete the lock document by hand while a run may be active |
| `LOCK_LOST` | the lease was lost mid-run; the interrupted migration is left `APPLYING` and **not** recorded applied | re-run; preflight/apply re-evaluate the live state |
| `TARGET_REFUSED` | ambiguous or disallowed target | set the environment (and the two confirmations outside local/test/dev) |
| `INVALID_SELECTION` | an id in `enabled-migrations` / `approved-data-migrations` is not in the registry (usually a typo); nothing was run, nothing created | correct the id and re-run (exit code 8) |
| stuck `APPLYING` | a crashed run | re-run after the lease expires |

## 11. Validator, data and reference rules

**Validator migrations** (`ValidatorMigration` — its preflight notes when an *existing, different* validator will be replaced; the previous options are captured for roll-back; the mechanism only — **DB-3 enables no new validator**; `V0001` still creates a *new* `products` collection with the existing strict `$jsonSchema` validator exactly as the legacy bootstrap did, and never alters an existing collection): conformance scan of existing documents (`find({$nor:[{$jsonSchema:…}]})`, bounded); controlled `collMod`; post-validation by reading the options back; previous options captured as `rollbackInfo` and restorable with `ValidatorMigration.restore`. `strict + error` with non-conforming documents is **BLOCKED**; `moderate` or `warn` tolerates them and says so. Documents are never rewritten. The proposed validators from `DATABASE_COLLECTION_CONTRACTS.md` use this path, after their §12.1 preconditions hold.

**Data migrations** (`kind = DATA`): never run without the id in `approved-data-migrations`; forward-only unless backed up; as narrow as possible. `V0004` changes only seed schema ids at `version: 1`; later authored versions and non-seed schemas are never touched (tested).

**Reference initialization** (`V0003`): insert-if-absent only. `TaxonomyLoader.load` no longer rewrites any persisted document (R3). The ratified U-4-f shape (`pack_size`/`pack_unit` not required) is applied to the **in-memory** seed copy at insert time, so a fresh database is unchanged; a regression suite proves a restart does not rewrite a later schema version or a persisted seed document.

## 12. Operational procedure

### 12.1 Existing database created by the legacy bootstrap (adoption)

1. `DRY_RUN` and review: `V0001` should report `WOULD_ADOPT`; `V0002`/`V0005`/`V0006`/`V0007`/`V0008`/`V0009`/`V0010`/`V0011`/`V0012`/`V0013`/`V0014` report what they would create or block.
2. Run the §9.1 and §9.2 preflight queries.
3. `APPLY` (with approvals/enables if intended). Adopted migrations are recorded without changing data.
4. Start the application in `VERIFY` mode.

### 12.2 Commands (the job is the same artifact, in job mode, one process, exits with the run's exit code)

```bash
# read-only report; no mutation, nothing created
TAZZZO_MIGRATION_MODE=DRY_RUN TAZZZO_MIGRATION_ENVIRONMENT=staging TAZZZO_MIGRATION_EXIT_AFTER_RUN=true \
TAZZZO_SCHEDULER_ENABLED=false TAZZZO_CONSUMER_RATE_LIMIT_MODE=DISABLED MONGODB_URI=... MONGODB_DATABASE=... \
java -jar catalog-service.jar --spring.main.web-application-type=none

# controlled apply (two-key confirmation outside local/test/dev)
TAZZZO_MIGRATION_MODE=APPLY TAZZZO_MIGRATION_ENVIRONMENT=staging TAZZZO_MIGRATION_EXIT_AFTER_RUN=true \
TAZZZO_MIGRATION_CONFIRM_DATABASE=<exact db name> TAZZZO_MIGRATION_CONFIRM_ENVIRONMENT=staging \
TAZZZO_MIGRATION_OPERATOR=<deploy job id> TAZZZO_BUILD_VERSION=<build> TAZZZO_SCHEDULER_ENABLED=false \
TAZZZO_CONSUMER_RATE_LIMIT_MODE=DISABLED MONGODB_URI=... MONGODB_DATABASE=... \
java -jar catalog-service.jar --spring.main.web-application-type=none \
  --tazzzo.migration.approved-data-migrations=V0004__seed_schemas_pack_fields_not_required   # only when approved
```

**The job is the application, so it needs the same mandatory configuration as the service** — notably `TAZZZO_CONSUMER_RATE_LIMIT_MODE`, which has no default and makes startup fail if unset (`DISABLED` is the fail-closed value for a job that serves nothing) — plus any other mandatory property the service requires at startup. A committed test (`MigrationJobContextIT`) starts the real application in exactly this shape (non-web, `DRY_RUN`, `exit-after-run=true`, only the configuration listed above) and checks that it exits with code 0 and creates nothing. Credentials come from the secret store (`MONGODB_URI`), never from this repository or the command line history. The migration identity should hold only the privileges it needs (`createCollection`, `createIndex`, `dropIndex`, `collMod`, read/write on the bookkeeping collections and, for data migrations, update on the target); the runtime application identity needs none of them in `VERIFY` mode. **Separate runtime / migrator / read-only identities, their exact role definitions (`docs/database/roles/`), the explicit connection contract and the startup verification that enforces both are DB-4: see `DATABASE_STAGING_RUNBOOK.md` (the verifier runs before the migration runner, and refuses to start a staging/production process whose connection string or privileges do not match).**

## 13. Rollback and roll-forward

| Kind | Strategy |
|---|---|
| index created | roll-forward: a new `DropIndexMigration`; the previous state is simply "no index" |
| index dropped | roll-forward: re-create from the recorded spec (`rollbackInfo.recreate`) |
| replace / rename | restore info recorded; a rename is reversed by a rename migration |
| validator | `ValidatorMigration.restore(db, rollbackInfo)` restores the previous options (or turns validation off if there was none) |
| reference init | insert-if-absent adds only; nothing to undo |
| data | **forward-only**; restore from backup (DB-6) — hence the narrow scope and the approval gate |

History records are never deleted; a corrective migration is a new one.

## 14. Unused-index decisions (DB-2 §6.2)

Usage re-verified against current `main` source by direct search (this phase). PR #49 (audit-read, **merged** as `8227286`) is accounted for: it adds nine `audit_read_*` partial indexes on `product_events`/`node_events`/`domain_events` and does **not** use the legacy ledger indexes for its default, actor, request-id, cursor or time-range queries.

| Index | Evidence on `main` | Decision | Preflight / rollout |
|---|---|---|---|
| `customer_sessions.session_by_customer` | every session read is `_id`-keyed (`isActive` filters `_id` + `customerId`…); no `customerId`-only query; PR #49 does not read sessions | **DROP-CANDIDATE** | `V0101` (disabled): drops only the exact definition; needs owner approval, then `enabled-migrations` |
| `canonical_keys (product_id)` | the only read is `_id`-keyed (`ProductQueryService.findByCanonicalKey`); `product_id` is never queried | **DROP-CANDIDATE** | `V0102` (disabled), same |
| `classification_history (product_id, decided_at)` | insert-only (`MintService`, `ClassifyService`), no reader; not an audit source in PR #49 | **WAIT** | keep until the owner decides whether a per-product history read is planned |
| `products.variant_group_id` (sparse) | no reader or writer; `variant_groups` is an unused collection | **WAIT** | decide together with retiring `variant_groups` |
| `product_events (product_id, at)` | insert-only in `WritePath`; highest-write ledger; PR #49 uses `audit_read_*`, and a `targetId` filter could use this index only for equality (it cannot supply the `(at,_id)` sort) | **WAIT** (PR #49 merged; re-evaluate drops with production write data) | re-evaluate after PR #49 merges; likely the first drop candidate (write amplification) |
| `node_events (node_id, at)` | insert-only (`TaxonomyChangeService`); PR #49 uses `audit_read_*` | **WAIT** (PR #49 merged; re-evaluate drops with production write data) | same |
| `domain_events (aggregate_type, aggregate_id, at)` | insert-only (`DomainAudit`); PR #49's `targetType`+`targetId` queries can use the equality | **WAIT** (PR #49 merged; re-evaluate drops with production write data) | same |
| `batches (product_id, lot_no)` unique | collection unused in main | **WAIT** | decide with collection retirement |
| `campaign_membership (campaign_id, product_id)` unique | collection unused in main | **WAIT** | decide with collection retirement |

Actual drops happen only through the disabled drop migrations after explicit approval; none is applied by default.

## 15. Production restrictions

- The application never mutates the schema on startup in `staging`/`production` (the guard refuses it).
- `APPLY` there requires the two-key confirmation and an identified environment; always `DRY_RUN` first and review.
- Run the §9 preflight queries before the first rollout of any new index; evaluate index build time and load on production-sized data first.
- Take a backup / confirm point-in-time recovery before any DATA migration or index drop (DB-6).
- Never delete or edit `schema_migrations` or the lock document by hand to "force" a run.
- Never edit an applied migration; add a new one.
- Duplicate remediation, validator `strict` rollouts and any data change are explicit human decisions, not automatic.

## 16. Interaction with PR #49 (audit-read) — resolved by `V0007`

PR #49 merged to `main` (`8227286`) and creates its nine `audit_read_*` partial indexes **inside
`SchemaBootstrap.bootstrap()`**. Since DB-3 the application no longer calls `bootstrap()` at startup and the
migration baseline (`V0001`) is driven by `IndexCatalog`, so a database built only by migrations would have lacked
them (the audit-read API would have run unindexed, bounded only by its 5 s `maxTime` and 100-row limit).

Resolution (merged into the DB-3 branch after PR #49 landed): `IndexCatalog.AUDIT_READ_SPECS` pins the nine
definitions, migration **`V0007__audit_read_partial_indexes`** creates them (adopting an identical existing index,
BLOCKING on a conflicting one, never `IndexOptionsConflict`), and `IndexContractIT` carries them in its independent
oracle (the earlier `audit_read_` name-prefix tolerance is removed). `MigrationFrameworkIT` asserts a migrations-only
database ends with all nine. The legacy `bootstrap()` (test/dev only) still creates the same nine; a drift test
pins that this is the only managed index it creates.

## 17. Not done in DB-3 (explicit)

No migration was run against any staging or production database; no validator is enabled; no unused index is dropped; PR #49's nine `audit_read_*` indexes are now part of the catalog and created by `V0007`; index build time on production-sized data and the live index set remain UNVERIFIED; the deployment pipeline integration is the AWS track. *(Update, DB-4: separate migration/runtime/read-only database identities, the connection contract and the fail-fast verifier are implemented and tested against a real authenticated replica set — `DATABASE_STAGING_RUNBOOK.md`. Nothing was run against Atlas.)*
