# DATABASE_STAGING_RUNBOOK — tazzzo-backend (DB-4)

What the backend needs from an Atlas **staging** database, how it connects, who may do what, and the exact
procedure to bring staging up. It closes DB-0 risk **R7** (every connection setting implicit) and the code half
of deployment gates **3, 5 and 6**; separating the migration identity from the runtime identity was explicitly
deferred to DB-4 by the migration runbook (§12.2, §17).

**Nothing in this document has been executed against Atlas, AWS or any persistent database.** The real-database
evidence below comes from throw-away Testcontainers (an authenticated MongoDB 7 replica set). Staging itself
does not exist yet (§1), so the live gates stay **UNVERIFIED**.

## 1. Status

| Item | State |
|---|---|
| Atlas staging environment | **PARTIAL — design decisions only.** The database name, the secret namespace, the runtime/migration identity split and an M0 plan are decided in the infrastructure repository. **No cluster, no connection string, no database users exist**; Atlas discovery is pending (its read-only service account and credentials file are not set up) and no AWS-to-Atlas connectivity is designed (no `27017` egress rule). |
| READY FOR STAGING DRY-RUN | **NO — blocked on infrastructure, not on code.** The code, role definitions, connection contract and procedure (§7) are ready; a dry run needs a cluster, the two users and their secrets. |
| Code | Connection contract, fail-fast verifier, least-privilege roles and their real-database tests are merged-ready in this PR. |
| Production | untouched. No production datastore work is started. |

### 1.1 Open blockers carried by this phase (full list: §12)

1. **R1 is FIXED IN CODE** (price-history retention PR): the hourly purge is removed and `price_events` is retained; the roll-up only aggregates legacy offer events and never touches paise ledger rows (`RollupService`, `PriceHistoryRetentionIT`). The scheduler therefore no longer has to be disabled *because of R1*. It still needs real-database proof on staging, and the other scheduler flags (card projection, freshness, reservation expiry) are separate decisions. Until staging evidence exists, keeping `TAZZZO_SCHEDULER_ENABLED=false` for the first runs is a conservative choice, not an R1 requirement.
2. `GET /api/v1/products` without `canonicalKey` answers 500 — **PRE-EXISTING DEFECT, OUTSIDE THE DATABASE FOUNDATION**; recorded, not fixed here.
3. Unindexed audit filters: decision pending staging evidence (§9.2).

## 2. What the infrastructure repository already decides (read-only inspection, 2026-10-04)

Source: `tazzzo-infrastructure` (branch `feature/infra-1-staging-foundation`; committed files only; no secret value was read, and the Atlas credentials file named in its `.gitignore` does not exist).

| Fact | Where |
|---|---|
| Staging database name **`tazzzo_staging`** | `terraform/staging/ssm.tf` ("runtime database user, read/write on tazzzo_staging only") |
| Runtime URI secret `/tazzzo/staging/backend/mongodb-uri`; migration URI secret `/tazzzo/staging/migration/mongodb-uri`, "NEVER readable by backend" | `terraform/staging/ssm.tf`, `docs/INFRA_1_PLAN.md` |
| Secrets live in SSM Parameter Store (SecureString, Standard tier), created by the operator out of band; Terraform never sees a value | `ssm.tf`, `docs/INFRA_1_PLAN.md` |
| Region `ap-south-1`; the `bootstrap/` stack is applied, `staging/` is planned but **not applied** | `terraform/README.md` |
| Plan: Atlas **M0** free tier; the migration job runs from the operator machine with only that IP on the Atlas allow-list | `docs/INFRA_0_DISCOVERY.md` |
| No `27017` egress from any AWS workload yet; M0 has no PrivateLink; Atlas connectivity "is not designed" | `docs/INFRA_1_PLAN.md` |

This runbook uses exactly those names and does not invent others. The Atlas user names used in the tests
(`tazzzo_app_runtime`, `tazzzo_app_migrator`, `tazzzo_app_migration_reader`) are suggestions for the infrastructure track.

## 3. Names

| What | Value | Status |
|---|---|---|
| Staging database | `tazzzo_staging` | established (infra repo) |
| Runtime URI secret | `/tazzzo/staging/backend/mongodb-uri` | established |
| Migration URI secret | `/tazzzo/staging/migration/mongodb-uri` | established |
| Read-only dry-run identity (optional) | no secret path decided | OPTIONAL; the migrator may also dry-run |
| Application collections | 49 (`SchemaBootstrap.COLLECTIONS`) + 2 migration bookkeeping (`schema_migrations`, `schema_migration_lock`) | code |
| Environment value for the app | `staging` (`TAZZZO_MIGRATION_ENVIRONMENT`) | code |

## 4. Identities and privileges (R5: separately permissioned)

### 4.1 Three identities, generated from one model

`PrivilegeModel` (code) is the single source of truth. The role files in `docs/database/roles/` are generated from
it and a test fails if they drift (`RoleFilesTest`; regenerate with `-Dtazzzo.regenerate-role-files=true`). They are MongoDB
`createRole` documents for database `tazzzo_staging` (substitute another environment's database name).

| Identity | Role file | Used by | Holds | Must NOT hold |
|---|---|---|---|---|
| **Runtime** | `tazzzo-runtime.role.json` | the normally started backend (mode `VERIFY`) | `find`/`insert`/`update`/`remove` on each of the 57 application collections; `find` on `schema_migrations`; database-level `listCollections` | any schema action (`createIndex`, `dropIndex`, `collMod`, `createCollection`, `dropCollection`, `dropDatabase`, rename), any write to `schema_migrations`, any access to `schema_migration_lock`, anything outside `tazzzo_staging`, any inherited built-in role |
| **Migrator** | `tazzzo-migrator.role.json` | the migration job (`APPLY`, and `DRY_RUN`) | database-wide `find`, `listCollections`, `listIndexes`, `createCollection`, `createIndex`, `dropIndex`, `collMod`; `insert`/`update` on the two bookkeeping collections; `insert`/`update` on the four seed collections (`taxonomy_nodes`, `aliases`, `attribute_definitions`, `attribute_schemas`) | writes to any business collection, `remove`, `dropCollection`, `dropDatabase`, user/role administration |
| **Migration reader** (optional) | `tazzzo-migration-reader.role.json` | a read-only `DRY_RUN` | database-wide `find`, `listCollections`, `listIndexes` | everything else |

Notes: the migrator reads every collection because the duplicate preflights (`aggregate`) scan the unique-index collections, some of which hold customer data — it is an operator-held identity that never runs inside the application. The privileges are
granted **per collection**, not database-wide, so that the runtime cannot touch the migration history (which would let a
compromised application forge "migrated" and defeat `VERIFY`) or the lock.

### 4.2 Enforced at startup, not just documented

`DatastoreStartupVerifier` (runs before the migration startup runner and every other `ApplicationRunner`; scheduled workers are held back separately, §6.1) compares what the connected identity **actually holds** (`connectionStatus` with `showPrivileges`) to the profile for the current mode:

| Mode | Profile | Refused when |
|---|---|---|
| `VERIFY` (default) | runtime | any required privilege is missing, **or any privilege beyond the runtime role is held** (a migrator or superuser connecting as the runtime is refused) |
| `DRY_RUN` | read | the read privileges are missing, or the identity exceeds the migrator ceiling |
| `APPLY` (and the local-only `APPLY_ON_STARTUP`/`LEGACY`) | migrator | a required migration privilege is missing, or anything beyond the migrator role is held |

### 4.3 Proven against a real server

`DatastorePrivilegeIT` provisions a real authenticated MongoDB 7 replica set (keyfile, root user), creates the three roles **from the model** and three users, then runs the real application as each. 14 tests, among them: the read-only identity dry-runs an empty database and creates nothing; the migrator applies V0001–V0007 and a read-only dry run then reports `ALREADY_APPLIED` for all; a re-apply changes nothing; the runtime identity inserts/finds/updates/removes on **every** application collection and commits a multi-collection transaction, and is refused with the server's own `Unauthorized` (code 13) for `createIndex`, `dropIndex`, `createCollection`, `collMod`, drop, `dropDatabase`, rename, `createUser`/`createRole`, any write to the history, any access to the lock, and any other or the admin database; the migrator is refused any write to `orders`/`customers`, `dropCollection`, `dropDatabase`, `createUser`. `RuntimeIdentityEndToEndIT` starts the **whole web application as the runtime identity** (schema built beforehand by the migrator job, mode `VERIFY`) and drives a service write, an HTTP product create, a read, a compare-and-set patch and a stale-version 409: none needed schema authority. `RuntimeNoSchemaAuthorityTest` guards the other half structurally: outside `catalog.migration`/`catalog.schema` no main class issues DDL or schema introspection (the single `runCommand` is the readiness `ping`).

### 4.4 Atlas specifics (MongoDB Atlas documentation, retrieved 2026-10-04)

Sources: <https://www.mongodb.com/docs/atlas/reference/free-shared-limitations/>, <https://www.mongodb.com/docs/atlas/unsupported-commands/>.

| Atlas fact for Free (M0) clusters | Consequence |
|---|---|
| Custom database roles are supported ("changes ... might take up to 30 seconds to deploy") | the least-privilege roles are expressible; allow 30 s after creating them |
| `createUser`, `createRole`, `usersInfo` are unsupported **as database commands** | create users and roles through the Atlas UI/API, not mongosh; the role files are the specification |
| Max 500 collections, 100 databases, 500 connections, 0.5 GB, **100 operations/second** | 59 collections (57 application + `schema_migrations` + `schema_migration_lock`) is fine; a 100 ops/s throttle will slow the migration job and any load test |
| `allowDiskUse` is **ignored** (treated as false) | a blocking in-memory sort beyond the server limit fails on M0 — relevant to the audit `targetId` path (§9.2) |
| Aggregation `maxTimeMS` is limited to 300 s; no `$where`/map-reduce; no Performance Advisor | n/a to this code; explain must be done by hand |
| **No backups**, no private endpoints, no rolling index builds | no restore drill is possible on M0 (§10); allow-list access only (matches the infra plan) |
| `hello`, `connectionStatus`, `collMod`, `createIndexes`, `dropIndexes`, `listIndexes` and **multi-document transactions** are **not mentioned** on those pages | **UNVERIFIED on M0.** M0 is a 3-node replica set and community material says transactions work, but nothing official was found. Step 1 of §7 verifies each on the real cluster **before anything else**. |

Atlas action names for the role files are expected to be `FIND`, `INSERT`, `UPDATE`, `REMOVE`, `LIST_COLLECTIONS`, `LIST_INDEXES`, `CREATE_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`, `COLL_MOD` — **UNVERIFIED**; confirm in the Atlas UI when the roles are created and compare the result with `connectionStatus` (step 1).

### 4.5 Secrets

Credentials appear only in the SSM-held URI. They are never in `application.yml`, Git, a Docker image, CI plaintext or a log: the verifier's and the contract's messages name options and collections only (tested to never contain a host, user, password or URI), and the migration history sanitizes errors. Fetch a URI into the environment without echoing it:

```bash
export MONGODB_URI="$(aws ssm get-parameter --region ap-south-1 --name /tazzzo/staging/migration/mongodb-uri \
                      --with-decryption --query Parameter.Value --output text)"   # operator profile; never the Terraform role
```

## 5. Connection contract (R7)

For `staging` and `production` the application **refuses to start** unless the connection string states every item below (`ConnectionContract`, pure, tested with 38 cases). Defaults are not accepted: each option must be present with the stated value. The same contract applies to the migration job.

| Option / property | Required | Why | Finding code |
|---|---|---|---|
| TLS | `tls=true`, or `mongodb+srv://` (not `tls=false`) | encrypted transport | `TLS_REQUIRED` |
| certificate/hostname validation | not relaxed (`tlsInsecure`, `tlsAllowInvalidCertificates`, `tlsAllowInvalidHostnames`) | authenticity | `TLS_VALIDATION_RELAXED` |
| credentials | present in the URI | dedicated identity per process | `CREDENTIALS_REQUIRED` |
| topology | `replicaSet=…` or `mongodb+srv://`; **no** `directConnection=true` | every write is a transaction | `REPLICA_SET_REQUIRED`, `DIRECT_CONNECTION_FORBIDDEN` |
| host | not loopback | this is what the localhost default of `MONGODB_URI` looks like when never set | `LOOPBACK_HOST_FORBIDDEN` |
| `retryWrites` | `true`, explicit | one retry of a transient failure | `RETRY_WRITES_REQUIRED` |
| `retryReads` | `true`, explicit | same | `RETRY_READS_REQUIRED` |
| `w` | `majority`, explicit | acknowledged writes survive a failover | `WRITE_CONCERN_MAJORITY_REQUIRED` |
| `readConcernLevel` | `majority`, explicit | reads never see rolled-back data | `READ_CONCERN_MAJORITY_REQUIRED` |
| `readPreference` | `primary`, explicit | reads follow writes; `MembershipRepository` already pins primary | `READ_PREFERENCE_PRIMARY_REQUIRED` |
| `connectTimeoutMS` | explicit, 1000–15000 | bounded failure | `CONNECT_TIMEOUT_REQUIRED`, `CONNECT_TIMEOUT_OUT_OF_RANGE` |
| `serverSelectionTimeoutMS` | explicit, 1000–30000 | an unreachable datastore fails startup in bounded time | `SERVER_SELECTION_TIMEOUT_REQUIRED`, `SERVER_SELECTION_TIMEOUT_OUT_OF_RANGE` |
| `socketTimeoutMS` | optional; `0` or ≥ 60000 | a tight socket timeout aborts an in-flight transaction with an ambiguous outcome | `SOCKET_TIMEOUT_TOO_LOW` |
| `waitQueueTimeoutMS` | optional; ≤ 30000 | bounded pool wait | `WAIT_QUEUE_TIMEOUT_OUT_OF_RANGE` |
| `maxPoolSize` | explicit, 1–100 (and `minPoolSize` ≤ it) | explicit capacity (M0 allows 500 connections in total) | `POOL_SIZE_REQUIRED`, `POOL_MIN_ABOVE_MAX` |
| `journal` | not `false` (unset or `true`) | `j:false` lets a `w=majority` write be acknowledged before it reaches the on-disk journal | `JOURNAL_DISABLED_FORBIDDEN` |
| `wtimeoutMS` | optional; `0` (no limit) or ≥ 1000 | a tiny value makes majority acknowledgement fail spuriously | `WTIMEOUT_TOO_LOW` |
| `replicaSet` | not blank | a blank name is not a replica set | `REPLICA_SET_REQUIRED` |
| revocation checking | `tlsDisableOCSPEndpointCheck` / `tlsDisableCertificateRevocationCheck` not `true` | revoked certificates must be refused | `TLS_REVOCATION_CHECK_DISABLED` |
| proxy | no `proxyHost` / `proxyPort` / `proxyUsername` / `proxyPassword` | a proxy changes the network route, so the target cannot be classified from the host list | `PROXY_FORBIDDEN` |

**PROPOSED — OWNER RATIFICATION REQUIRED.** Every *numeric* limit in this table (`connectTimeoutMS` ≤ 15000, `serverSelectionTimeoutMS` ≤ 30000, `socketTimeoutMS` ≥ 60000,
`waitQueueTimeoutMS` ≤ 30000, `maxPoolSize` ≤ 100, `wtimeoutMS` ≥ 1000) was chosen by the DB-4 implementation as a conservative starting point. **No owner has approved
them**; they are validation thresholds, not ratified requirements, and the pool cap in particular may need to change once production sizing is known. The non-numeric rules
(TLS, credentials, replica set, retries, `w=majority`, `readConcernLevel=majority`, `readPreference=primary`) follow the ratified intent of risk R7. The `journal` and `wtimeoutMS`
rules close review findings: Atlas/driver documentation was not consulted for a tier-specific default, so they state only what the connection string says.

Not a contract item, but a requirement: **transactions**. `Tx` calls `withTransaction` with no `TransactionOptions`, so
transactions inherit the client's concerns — the explicit `w=majority` / `readConcernLevel=majority` above therefore
define transaction semantics too, which is what deployment gates 5 and 6 could not previously establish. The server must be a replica set (or `mongos`) with sessions and at least MongoDB 7.0 (wire version 21): `TopologyCheck`.

Template (placeholders only; the password is a secret and is never written here):

```
mongodb+srv://<user>:<password>@<cluster-host>/tazzzo_staging?authSource=admin&retryWrites=true&retryReads=true&w=majority&readConcernLevel=majority&readPreference=primary&connectTimeoutMS=10000&serverSelectionTimeoutMS=15000&maxPoolSize=20
```

Configuration reference (no value here is a secret):

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `MONGODB_URI` | `spring.mongodb.uri` | localhost (**refused** for staging/production) | the connection string — secret |
| `MONGODB_DATABASE` | `spring.mongodb.database` | `tazzzo` | `tazzzo_staging` for staging |
| `TAZZZO_MIGRATION_ENVIRONMENT` | `tazzzo.migration.environment` | unset | `local`/`test`/`dev`/`staging`/`production`. **A label, not a boundary:** enforcement is also decided by the connection string (§6.2) |
| `TAZZZO_MIGRATION_MODE` | `tazzzo.migration.mode` | `VERIFY` | `VERIFY` (service), `DRY_RUN` / `APPLY` (job) |
| `TAZZZO_DATASTORE_PRIVILEGE_VERIFICATION` | `tazzzo.datastore.privilege-verification` | `AUTO` | `AUTO` enforces for staging/production; `ENFORCE` enforces everywhere. **There is no setting that turns enforcement off for staging or production.** |
| `TAZZZO_SCHEDULER_ENABLED` | `tazzzo.scheduler.enabled` | `true` | no longer forced to `false` by R1 (fixed in code); the startup gate in §6.1 holds workers back until the datastore is verified. Keep `false` for the very first staging runs if you want a conservative start |

## 6. Startup behaviour (fail-fast)

The verifier runs before the migration startup runner and every other `ApplicationRunner`, and stops at the first failing stage. It never mutates and never "fixes" the schema to make startup pass.
What its ordering does **not** do is hold back `@Scheduled` workers, which start at context refresh, before any runner: that is closed off by the readiness gate (§6.1), not by ordering.

| Condition | Result (finding code) |
|---|---|
| `staging`/`production`, **or any label with a non-local target (§6.2)**, URI missing or violating the contract | refuse, no connection attempted by the verifier (`URI_MISSING`, `URI_INVALID`, and the §5 codes) |
| environment unset **and** a non-loopback server | refuse (`ENVIRONMENT_NOT_IDENTIFIED`) — the environment cannot be trusted |
| environment not one of the five known names | refuse (`ENVIRONMENT_UNKNOWN`) |
| server unreachable within `serverSelectionTimeoutMS` | refuse (`DATASTORE_UNAVAILABLE`) |
| connection string cannot be resolved (for example an SRV lookup fails) | refuse (`DATASTORE_CONFIGURATION_ERROR`) |
| any other driver failure during the checks | refuse (`DATASTORE_CHECK_FAILED`, naming only the failed check and the exception class) |
| wrong credentials | refuse (`AUTHENTICATION_FAILED`) — message never contains the user or password |
| identity may not run `hello`/`connectionStatus` | refuse (`UNAUTHORIZED_COMMAND`) |
| standalone server / no sessions / older than MongoDB 7.0 | refuse (`TRANSACTIONS_UNSUPPORTED_TOPOLOGY`, `SESSIONS_UNSUPPORTED`, `SERVER_VERSION_TOO_OLD`) |
| identity not authenticated, missing a required privilege, holding an excess privilege, or holding any privilege outside the application database | refuse (`NOT_AUTHENTICATED`, `MISSING_PRIVILEGE`, `EXCESS_PRIVILEGE`, `OUT_OF_SCOPE_PRIVILEGE`) |
| migrations not applied (VERIFY) | refuse — existing behaviour of the migration runner (`database schema verification failed`) |
| `local`/`test`/`dev` **and a loopback-only, non-SRV target** | advisory only: the findings are logged on one INFO line, the database is not touched by the verifier |

`DatastoreWiringIT` proves the runner ordering: a fully authorized `APPLY` job in `staging` (migrator credentials, both confirmations correct) is refused for an out-of-contract URI **before the migration runner mutates anything**; `production` with no `MONGODB_URI` fails in well under 20 s instead of falling back to localhost. Those tests run with the scheduler disabled; the scheduled-worker guarantee has its own proof (§6.1).

### 6.1 Scheduled workers and the readiness gate

`@Scheduled` methods start at context refresh, **before** any `ApplicationRunner`. Before this hardening a refused startup (and a migration job) could therefore already have run the
price rollup and purge, the merge/taint/stamp/backfill workers and the projection and reservation workers (observed: `PRICE_ROLLUP`/`PRICE_PURGE` events written and `price_events` rows deleted
by a process the verifier then refused). The invariant now enforced **in code** is:

> No scheduled business worker acts until the datastore verification has succeeded **and** startup has finished in a serving mode.

How: the application's only `TaskScheduler` is `GatedTaskScheduler`; every `@Scheduled` method is registered through it, so each tick first asks `DatastoreReadiness.workersPermitted()` and returns
without touching anything if the answer is no. The gate is closed at the first instant and opens once, at the end of a successful startup:

| State | Entered when | Workers |
|---|---|---|
| `PENDING` | process start | blocked |
| `VERIFIED` | `DatastoreStartupVerifier` succeeded (enforced and passed, or advisory) | blocked (startup is not finished) |
| `OPEN` | `MigrationStartupRunner` completed in `VERIFY` / `APPLY_ON_STARTUP` / `LEGACY` after a verified datastore | **run** (first tick one period after opening) |
| `REFUSED` | the verifier refused startup | blocked, terminal |
| `JOB` | mode `DRY_RUN` or `APPLY` | blocked, terminal: a migration job runs no business workers whatever `TAZZZO_SCHEDULER_ENABLED` says |

This does **not** depend on `TAZZZO_SCHEDULER_ENABLED=false`, on runner order or on timing. All nine `@Scheduled` methods (`CatalogSchedulers` ×5, `CommerceProjectionScheduler` ×2, `InventoryReservationScheduler` ×1, `NotificationConfig.Dispatch` ×1: the notification outbox dispatcher, idempotent through leased claims with token-conditional completion, off unless `tazzzo.scheduler.notification-dispatch-enabled` and a provider exists) are covered, and
`ScheduledWorkerGateTest` fails if a ninth appears without being inventoried, if a second scheduler or timer is introduced, or if a scheduling entry point of the gated scheduler is not wrapped.
`StartupSchedulerGateIT` proves it against a real MongoDB with the scheduler **enabled** and periods of 10 ms: a verifier refusal, a later migration-runner refusal and a `DRY_RUN` job each leave the price ledger and the
whole database untouched; a healthy start opens the gate.

**What it does not do.** (a) It is not what protects price history: that is the R1 fix itself (no purge exists; `price_events` is retained), so a verified, OPEN process running `priceRollup` deletes nothing.
(b) Beans created during context refresh may open lazy connections; none writes. (c) Business workers are held until the migration runner finishes, which for `APPLY_ON_STARTUP` (local/dev only) also keeps them from racing the migration.

### 6.2 The environment label is metadata, not a boundary

An environment name says what the operator *intends*; it cannot make a remote database safe. The relaxed `local`/`test`/`dev` treatment (advisory verification, startup mutation, `APPLY` without a confirmation) is therefore granted **only to a demonstrably
local target**: a plain (non-SRV) connection string, with **no proxy option**, whose every host is a *provable* loopback (below). Otherwise the target is enforced exactly like production. The decision is made once (`ConnectionContract.isLocalTarget`) and drives both the startup verifier and DB-3's `TargetGuard` (through `MigrationTarget.local`):

| Label | Target | Verifier | `TargetGuard` |
|---|---|---|---|
| `staging` / `production` | any | enforced | no startup/legacy mutation; `APPLY` needs the two-key confirmation |
| `dev` / `test` / `local` | provable loopback only, no proxy (`localhost`, `localhost.`, a standard dotted-quad in `127.0.0.0/8`, a single decimal integer in that range such as `2130706433`, `::1`, IPv4-mapped `::ffff:127.x.x.x`) | advisory | unchanged: startup/legacy mutation and `APPLY` allowed |
| `dev` / `test` / `local` | an SRV name, a remote hostname, a public or private-network IP, an ambiguous IPv4 spelling (`0177.0.0.1`, `0x7f000001`, `127.1`), the wildcard `0.0.0.0`, **any `proxyHost`/`proxyPort`/`proxyUsername`/`proxyPassword`**, a mix of loopback and any other host, or an unparseable string | **enforced** | startup/legacy mutation refused; `APPLY` needs the two-key confirmation |
| unset | non-local | enforced (`ENVIRONMENT_NOT_IDENTIFIED`) | refused |
| unset | loopback | advisory | refused to mutate (environment not identified) |

No DNS lookup is ever made to decide this: only strictly shaped literals ever reach the JDK parser, a name other than `localhost` is never trusted to resolve to loopback, a private-network address (`10.x`, `172.16/12`, `192.168.x`) is **not** treated as
local, and a hostname that merely starts with `127.` is not loopback. The classifier is built around what the JDK/driver actually connects to: the JDK reads a leading-zero quad such as `0177.0.0.1` as **decimal** `177.0.0.1`
(a different, routable address) and does not resolve `0x7f000001` at all, so every legacy `inet_aton` spelling (octal, hex, leading zeros, short forms such as `127.1`) is *not local*; `0.0.0.0` / `::` are bind wildcards, not destinations, and are not local either.
A proxy changes the effective network destination, so a loopback host behind any proxy option is not proven local. Proxy use is decided from the MongoDB driver's own parse of the connection string (its effective proxy settings), not from a second text scan, so it holds for both option delimiters the driver accepts (`&` and `;`), in any letter case; a string the driver cannot turn into settings counts as proxied. The staging/production contract refuses it too (`PROXY_FORBIDDEN`). In the other direction the "no loopback host" contract rule for staging/production stays deliberately over-inclusive
(it still refuses `127.1`, `0177.0.0.1`, `0.0.0.0` and the rest), so no spelling can slip through either rule. There is no developer opt-in flag. (`tazzzo.datastore.privilege-verification=ENFORCE` still enforces everywhere.)

**Residual limit.** A loopback address does not prove the data is local: an SSH or `kubectl` port-forward to a remote cluster, or replica-set discovery that advertises remote member names, looks local. The label and the literal address cannot detect that; use an explicit
`staging`/`production` label, or `ENFORCE`, whenever the datastore is not a throw-away local one.

### 6.3 Driver logging

The MongoDB Java driver itself logs one INFO line (logger `org.mongodb.driver.client`) when a client is created, showing the effective settings — useful evidence for deployment gates 5 and 6 — and it includes the **database user name** and
host names (never the password). That is driver behaviour, not application code, and the driver is not patched. Decision: keep INFO for the first staging runs so the line can be attached as evidence (redact the user name when attaching it to a
ticket), then set `logging.level.org.mongodb.driver.client=WARN` for steady-state staging and production if user names should not be in logs. No logging configuration is changed by this PR.

## 7. Staging procedure

Preconditions (infrastructure track): an Atlas cluster, the runtime and migrator users created from the role files (Atlas UI/API), both URIs stored in SSM, the operator's IP on the Atlas allow-list. **Do not start without all four.** The migration job is the same artifact as the service in job mode (migration runbook §12.2); it needs the mandatory configuration listed there.

| # | Step | How | Pass criteria / abort |
|---|---|---|---|
| 1 | **Connectivity and capability test** (operator machine, migrator URI) | `mongosh "$MONGODB_URI" --quiet --eval 'printjson(db.getSiblingDB("admin").runCommand({hello:1}))'` then the same with `{connectionStatus:1, showPrivileges:true}`. Then, **as the Atlas project administrator in a throw-away database `tazzzo_conn_check` (never `tazzzo_staging`)**, run a two-document transaction with `readConcern`/`writeConcern` `majority`, then drop that database. | `hello`: `setName` present, `maxWireVersion ≥ 21`, `logicalSessionTimeoutMinutes` present. `connectionStatus` privileges equal the migrator role file. The transaction commits. **Any failure here (especially transactions or `connectionStatus` on M0) is a stop: report it, do not continue.** |
| 2 | **Dry run** | `TAZZZO_MIGRATION_MODE=DRY_RUN TAZZZO_MIGRATION_ENVIRONMENT=staging TAZZZO_MIGRATION_EXIT_AFTER_RUN=true TAZZZO_SCHEDULER_ENABLED=false TAZZZO_CONSUMER_RATE_LIMIT_MODE=DISABLED MONGODB_DATABASE=tazzzo_staging java -jar catalog-service.jar --spring.main.web-application-type=none` (URI from §4.5) | exit `0`. The datastore verifier line `datastore verified: … profile=MIGRATION_READ … privileges=ok` precedes the report. |
| 3 | **Review** | read the report | on an **empty** database expect exactly the output below. Anything else (a `BLOCKED` step, a `WOULD_ADOPT` other than V0004) means the database is not empty or not what we think: stop and investigate. |
| 4 | **Apply** | as step 2 with `TAZZZO_MIGRATION_MODE=APPLY TAZZZO_MIGRATION_CONFIRM_DATABASE=tazzzo_staging TAZZZO_MIGRATION_CONFIRM_ENVIRONMENT=staging TAZZZO_MIGRATION_OPERATOR=<deploy job id> TAZZZO_BUILD_VERSION=<build>` | exit `0`; the outcome is `OK`. A fresh database needs no `approved-data-migrations`: V0004 is adopted because there is nothing to change. |
| 5 | **Verify** | `mongosh` as the migrator: `db.schema_migrations.find({},{status:1,checksum:1})` | seven documents, all `APPLIED`; `db.getCollectionNames().length` is 51 (49 + 2 bookkeeping) |
| 6 | **Second dry run** | repeat step 2 | |
| 7 | **Zero pending** | read the report | every step `ALREADY_APPLIED` (also shown below) |
| 8 | **Restart-safe / idempotency** | repeat step 4 once more, then start the **service** with the **runtime** URI in the default `VERIFY` mode | the re-apply changes nothing (`attempts` and `appliedAt` unchanged); the service starts and logs `datastore verified: … profile=RUNTIME … privileges=ok` |
| 9 | **Scheduler** | decide the scheduler setting for the service (R1 no longer forces `false`) | after the first verified start, confirm on the real database that `price_events` row counts never decrease across scheduler runs. The startup gate (§6.1) protects a refused or job process |

Real output on an empty database, captured from `DatastorePrivilegeIT` (read-only identity, real authenticated MongoDB 7):

```
DRY-RUN OK target[environment=… database=tazzzo_staging hosts=[…] operator=… build=…]
  V0001__baseline_schema: WOULD_APPLY (would mutate)
  V0002__products_vertical_id_cursor_index: WOULD_APPLY (would mutate)
  V0003__taxonomy_seed_0_9_0: WOULD_APPLY (would mutate)
  V0004__seed_schemas_pack_fields_not_required: WOULD_ADOPT
  V0005__evidence_links_unique_link: WOULD_APPLY (would mutate)
  V0006__taxonomy_nodes_unique_active_sibling_name: WOULD_APPLY (would mutate)
  V0007__audit_read_partial_indexes: WOULD_APPLY (would mutate)
```

and after the apply, the second dry run:

```
DRY-RUN OK ...
  V0001__baseline_schema: ALREADY_APPLIED   ...   V0007__audit_read_partial_indexes: ALREADY_APPLIED
```

The migration runbook (§4, §9–§10) covers adoption of an existing database, the `products` index preflight, duplicate remediation and every failure outcome.

## 8. Transactions and compare-and-set

### 8.1 Transactions

`Tx.run`/`Tx.call` are the only transaction entry points (`session.withTransaction`, no options → client concerns, §5). **32 main classes** use them (counted by source search, excluding `Tx` itself) across catalog, auth, customer, commerce read, inventory, membership, pricing, media and serviceability. The full per-transaction map (collections in write order, rollback, duplicate-key/idempotency/CAS) is `DATABASE_INVENTORY.md` §10 and is authoritative; the staging requirements it implies are:

- a **replica set** (`TRANSACTIONS_UNSUPPORTED_TOPOLOGY` otherwise) — Atlas M0 is a 3-node replica set; transaction support there is UNVERIFIED (§4.4) and verified by step 1;
- `ClientSession.withTransaction` may run the callback **more than once** on a transient error, so callbacks must be retry-safe (the codebase's rule; `Tx` documents it) — idempotency per transaction is in the inventory map;
- writes cross collections inside one transaction (for example mint: identity keys → canonical keys → product → history → work queue → events; order placement: re-read → reserve inventory → insert order), so the runtime role needs write access to all of them in the same identity (it has it, §4.1).

### 8.2 Compare-and-set / versioning

| Module | Collection | Mechanism | Evidence |
|---|---|---|---|
| catalog | `products` | filter `{_id, version:(int)expected}` + `$inc` (`WritePath.casUpdateWithEvent`; classify/publish/gtin/title/lifecycle/merge) | `WritePath`, `ProductController` (`If-Match`); exercised under least privilege by `RuntimeIdentityEndToEndIT` (patch, then stale → 409) |
| catalog | `taxonomy_nodes` | `version` CAS (`casNode`) | `TaxonomyChangeService` |
| catalog | `attribute_*`, releases | `ReleaseGate` `$inc change_seq`; unique `(key,version)` / `(schema_id,version)` | `AttributeAuthoringService` |
| customer | `customer_profiles`, `customer_addresses`, `customer_carts` | `{_id, version}` long CAS (`0` ⇒ upsert for profiles); `replaceItemsIfVersion`, `clearExpiredIfVersion` | repositories |
| customer | `customer_address_state` | single-document limit CAS (`$lt` upsert) | `AddressRepository` |
| customer | `orders` | unique `(customerId, quoteId)`; single `CREATED→CONFIRMED` shape | `OrderRepository` |
| membership | `memberships` | unique `(grantSource, grantRef)`; partial unique `openTerm:true`; CAS on status markers | `MembershipRepository` |
| pricing / inventory | `price_current`, `inventory`, `inventory_reservations` | `version` CAS; `reserved <= on_hand` via `$expr`; header CAS on `status`; unique `orderId` | `PricingService`, `InventoryService` |
| media / serviceability | `media_refs`, `service_areas` | `version` CAS | services |
| auth | OTP challenges, sessions | filter-CAS on `status` / presented digest (no version field) | `Otp*`, `CustomerSession*` |

All their indexes and uniqueness guards are pinned by `IndexContractIT`. 17 main classes contain version CAS write sites.

## 9. Query-plan verification

### 9.1 Critical paths — evidence today and what staging must prove

Existing committed evidence (real MongoDB 7 Testcontainers) is retained. **None of it is staging evidence**: planner choices depend on data volume, skew and server version. On staging, after realistic data exists, run `explain("executionStats")` for each path and record the result.

| Path | Collection (index) | Shape | Committed evidence | Staging pass criteria |
|---|---|---|---|---|
| Catalogue product list / keyset cursor | `products` (`classification.vertical_id_1_lifecycle_1_classification.status_1__id_1`, `product_vertical_id_cursor`) | vertical + lifecycle/status, `_id` cursor, limit | `IndexContractIT` (per-vertical ordered scan, no SORT) | `IXSCAN`, no `SORT`, keys/docs examined ≈ returned |
| Product by id / by canonical key | `products` `_id`, `canonical_keys` `_id` | point | trivial | `IXSCAN _id_` |
| Taxonomy children / snapshot walk | `taxonomy_nodes` (`parent_id_1`), `taxonomy_snapshot_nodes` (`release_id_1_node_id_1`, `release_id_1_parent_id_1`) | equality | `IndexContractIT`, snapshot IT | `IXSCAN` |
| Inventory | `inventory` (`sku_id_1_fulfillment_location_id_1`) | point / batch `$in` | `IndexContractIT` (unique) | `IXSCAN` |
| Pricing | `price_current` (`sku_id_1_currency_1`) | point / batch `$in` | `IndexContractIT` (unique) | `IXSCAN` |
| Orders | `orders` (`order_one_per_quote`) | `(customerId, quoteId)` | `IndexContractIT`, `OrderServiceIT` | `IXSCAN` |
| Reservation | `inventory_reservations` (`inventory_reservation_one_per_order`, `inventory_reservation_expiry`) | `orderId`; `status`+`expiresAt` | `IndexContractIT` | `IXSCAN` |
| Membership entitlement | `memberships` (`membership_active_by_customer`, `membership_one_open_per_customer`) | `customerId` + status/openTerm | `MembershipRepositoryIT:143-155` | `IXSCAN`, bounded scan |
| Customer / session (durable) | `customers` (`customer_one_per_phone`), `customer_sessions` (`_id`) | point | `IndexContractIT` | `IXSCAN` |
| **Audit newest page** | `product_events` / `node_events` / `domain_events` (`audit_read_recent`) | partial predicate + `(at,_id)` sort + cursor | `AuditReadIndexIT` | `IXSCAN audit_read_recent`, no `COLLSCAN`/`SORT`, docs examined ≈ limit+1 |
| **Audit actor** | same (`audit_read_actor`) | `actor.id` equality | `AuditReadIndexIT` | `IXSCAN audit_read_actor` |
| **Audit requestId** | same (`audit_read_request`) | `actor.request_id` equality | `AuditReadIndexIT` (1 key, 1 doc) | `IXSCAN audit_read_request`, ≤ 1 doc per ledger |

Procedure per path (read-only, migrator or reader identity): `db.<collection>.find(<filter>).sort(<sort>).limit(<n>).explain("executionStats")`; record `winningPlan` stages, `totalKeysExamined`, `totalDocsExamined`, `nReturned`, `executionTimeMillis`; fail on `COLLSCAN` or a blocking `SORT` for any path above.

### 9.2 Unindexed audit filters — decision state: **PENDING STAGING EVIDENCE**

`actorType`-only, `action`-only and `targetType`/`targetId` have no dedicated audit index (by design: low cardinality; no speculative index). Expected path: walk `audit_read_recent` newest-first and filter on the fetched document until `limit+1` rows or the **5 s `maxTime`**. One-off, **uncommitted** local measurement on a real MongoDB 7 container (40,000 attributed rows per ledger): a filter that matches nothing examined 40,000 keys and 40,000 documents per ledger (127 ms for the three ledgers); a `targetType=product&targetId=…` query uses the legacy `product_id_1_at_1` index plus an in-memory sort of that product's events (80 keys for 51 returned), and `taxonomy_node` likewise (`node_id_1_at_1`, 800 keys). Cost is therefore **linear in ledger size** for a selective filter. Two cautions: `TZP-SYSTEM` is a pseudo-target used by taxonomy/system events and grows without bound, and on Atlas M0 `allowDiskUse` is ignored.

Decision after staging evidence (do not add an index before it): **KEEP CURRENT INDEXES** if the worst filter stays well under 5 s at realistic volume; **ADD INDEX** if a filter routinely approaches the limit; otherwise **map the timeout to a controlled 503** (today it surfaces as a generic 500 with no metric outcome). Required evidence: expected ledger sizes from the owner, then the §9.1 procedure for `action=<rare>`, `actorType=SYSTEM`, `targetType=product&targetId=TZP-SYSTEM`.

## 10. Backup, restore and retention requirements (DB-6 inputs — not configured here)

Atlas backup is infrastructure-owned; this section is the database's requirement.

| Requirement | Staging | Production |
|---|---|---|
| Automated backup | desirable; **not available on Atlas M0** | **required** |
| Point-in-time recovery | not required | **required** |
| RPO / RTO | TBD — to be decided with infrastructure and the business | TBD — to be decided with infrastructure and the business |
| Restore procedure | restore into a scratch cluster, never over the live one | same; documented step by step before go-live |
| Restore verification | after a restore run the migration **dry run** (expect all `ALREADY_APPLIED`, zero pending), start the service in `VERIFY`, run the §9.1 explains, compare document counts of the durable collections | same |
| Before any DATA migration or index drop | take/confirm a backup (migration runbook §15) | mandatory |

**A production database cannot be marked READY until a restore has actually been performed and verified** (DB-8 gate). Backups contain PII and the audit ledgers; erasure and retention design must account for them (`DATABASE_RETENTION_AND_PII.md`). Retention of the event ledgers is **UNDECIDED / DEPLOYMENT POLICY**: they have no TTL and no deletion (`product_events`, `node_events`, `domain_events`; asserted by `IndexContractIT`).

## 11. Boundaries: migrations, seeds, imports, business data

| Kind | Allowed in a migration? | Rule |
|---|---|---|
| Schema (collections, indexes, validators) | yes | versioned, locked, recorded; the migrator identity only |
| Reference seed (`V0003` taxonomy 0.9.0) | yes, because it is already authoritative | insert-if-absent; never rewrites a persisted document (R3) |
| Seed data change (`V0004`) | only when approved | narrow, `version: 1` seed schemas only |
| Catalogue imports (the ~5,000 SKUs) | **no** | separate, validated, de-duplicated, repeatable, idempotent/upsert-safe; **not part of this PR**; runtime/ingest identity, never the migrator |
| Synthetic customers, orders, payments, historical audit events | **never** | migrations must not insert them |

## 12. Open items and blockers

| # | Item | State | Owner |
|---|---|---|---|
| 1 | ~~R1 conflict: hourly price rollup/purge deleted rolled `price_events` rows~~ | **FIXED IN CODE** (purge removed, ledger retained, no migration); real-staging confirmation pending | verify on staging (§7 step 9) |
| 2 | `GET /api/v1/products` without `canonicalKey` returns 500 (unsatisfied request-parameter condition mapped to a generic 500) | PRE-EXISTING DEFECT — OUTSIDE DB FOUNDATION; reproduced at an older head; to be fixed separately | backend |
| 3 | Atlas staging cluster, users, secrets, allow-list, AWS-to-Atlas connectivity | not provisioned | infrastructure track |
| 4 | M0 behaviour of transactions, `connectionStatus`, `collMod`, index DDL; Atlas action names | UNVERIFIED | step 1 of §7 |
| 5 | Audit unindexed-filter decision (§9.2) | PENDING staging evidence | DB-7 |
| 6 | Backup/PITR/restore drill, RPO/RTO, retention matrix policy values, erasure design | TBD — PRODUCTION POLICY | DB-6 |
| 7 | Catalogue/reference ingestion contract; fresh-DB `catalogue_releases` baseline | not started | DB-5 |
| 8 | Query/index/load verification on real data; multi-node replica set; drift detection | not started | DB-7 |
| 9 | Production datastore readiness gate (all six deployment gates PASS with live evidence) | not started; the six gates stay PENDING / UNVERIFIED | DB-8 |
