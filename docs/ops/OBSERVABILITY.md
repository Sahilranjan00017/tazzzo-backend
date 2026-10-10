# Observability: metrics, logs and health

What the service itself emits, independent of any monitoring product. Nothing here needs AWS. The decision about **where
these signals are collected and who is paged** is deployment-level and still open (section 6).

## 1. Rules every meter follows

- **Bounded tags.** A tag value is a string literal, the lower-cased name of an enum constant, or a Spring route pattern
  declared in code (admin HTTP meter only). Never an id, URI, SKU, job id, owner id, asset key, content type, IP, phone,
  e-mail, token, header or exception message. Pinned by tests that scan the whole registry after real flows
  (`ImportMetricsIT`, `MediaMetricsIT`, `ObservabilityHttpIT`) and by `ObservabilityGuardTest` (static: tag values and log
  arguments in the observability classes).
- **Recording never fails a request.** Every meter class swallows its own errors and logs the exception class only.
- **Database-backed gauges are cached snapshots.** They are re-read at most once per
  `tazzzo.observability.gauge-refresh-seconds` (`TAZZZO_OBSERVABILITY_GAUGE_REFRESH_SECONDS`, default 15, range 1..3600)
  however often they are scraped, by one caller at a time (others keep reading the previous snapshot). Every query is
  capped and time-bounded (2 s). A failed refresh keeps the last good snapshot and is retried one interval later. Before
  the first success a gauge reads `NaN`. A gauge that reads `NaN` for long means its database query is failing or timing out.
- **Counters are per instance and reset on restart.** Compute rates over a window, not absolute values.
- The notification outbox gauges keep their own interval (`tazzzo.notifications.metrics-refresh-seconds`).

## 2. New in this change

### 2.1 Asynchronous import jobs (`/api/v1/admin/imports/jobs`)

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `import_job_transitions` | counter | `to` = open, validating, validated, rejected, applying, paused, completed, cancelled | A job entered that status (creation counts as `open`). Counted when the guarded database update succeeded, so a lost compare-and-set is not counted. |
| `import_rows_applied` | counter | none | A row was minted and its verdict recorded. A row minted by a worker that then lost its lease is recorded by nobody and not counted. |
| `import_rows_failed` | counter | none | A row failed on its own terms at apply (identity collision, governance change, schema violation) and the verdict was recorded. |
| `import_job_lease_lost` | counter | `phase` = validate, apply | The worker stopped because its lease was lost or the job was cancelled under it. |
| `import_job_paused` | counter | `reason` = datastore_failure, ledger_short, no_approver | The worker paused a job. `datastore_failure` is counted only when the pause was recorded. |
| `import_worker_tick_duration` | timer | `result` = idle, worked, paused, error, claim_failed | One worker tick from claim to release. `idle` ticks are the polling cost; `error` is an unexpected exception inside a tick. |
| `import_jobs_active` | gauge | `status` = open, validating, validated, rejected, applying, paused | Non-terminal jobs per status (each count capped at 10000). Completed and cancelled jobs are history, not backlog. |
| `import_job_oldest_active_age_seconds` | gauge | none | Seconds since the least recently progressed job in `validating` or `applying` last updated (every claim, lease renewal and recorded batch advances it); 0 when the worker owns nothing. A merely long import keeps it near 0; a stalled or absent worker lets it grow. |

Alert intent: page when `import_job_oldest_active_age_seconds` stays above a few lease lengths (the default lease is 300 s)
while `import_worker_tick_duration{result="idle"}` has stopped (no worker is polling) or `error` / `claim_failed` ticks are
rising. Warn on any increase of `import_job_paused`, which needs an admin to resume; warn on a sustained non-zero rate of
`import_job_lease_lost` (workers fighting over leases or lease shorter than a batch); review any `import_rows_failed` increase
after a run (the failed rows are in the job's error file). A growing `import_jobs_active{status="validated"}` or `paused`
means jobs waiting for a human.

### 2.2 Synchronous bulk import (`/api/v1/admin/imports/{prices,inventory,products}`)

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `bulk_import_runs` | counter | `kind` = prices, inventory, products, other; `outcome` = dry_run, rejected, applied, partial, failed, stopped, unchanged | One run. `rejected`: the whole file was refused (whole-file validation, nothing written). `stopped`: the datastore failed mid-run and the rest were not attempted. `partial`: some rows applied, some failed. `unchanged`: every row was already in the requested state. |
| `bulk_import_rows` | counter | `kind`, `outcome` = applied, failed, unchanged, not_attempted, validated | Rows by outcome. `validated` is the non-unchanged rows of a dry run. |

Alert intent: `stopped` is the only outcome that signals the platform (datastore) rather than the file; warn on any. A rising
`rejected` or `failed` share is operator-data quality, a ticket rather than a page.

### 2.3 Media admin flow

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `media_upload_presign` | counter | `outcome` = ok, rejected, owner_not_found, not_configured, storage_error, error | A direct-to-storage upload target was requested. `rejected` is a policy refusal (type, size, owner type); `storage_error` is the store failing to presign. |
| `media_verify` | counter | `outcome` = ok, missing_object, size_mismatch, type_mismatch, sniff_reject, extension_mismatch, dimension_reject, pixel_reject, dimension_mismatch, key_not_issued, not_configured, storage_error | A newly referenced asset key was checked against what storage holds. `size_mismatch`: stored size outside 1..ceiling. `type_mismatch`: declared or stored content type disagrees with the bytes. `sniff_reject`: the bytes are not an allowed image. `extension_mismatch`: the key's extension disagrees with the bytes. `dimension_reject` / `pixel_reject`: the header's width or height, or width times height, exceeds the configured maximum. `dimension_mismatch`: declared width/height differ from the stored image. (`sniff_reject` also covers a header that cannot be read as an image.) `key_not_issued`: the key was not issued for this owner. `not_configured`: no storage is configured and this environment (not local/test/dev) refuses unverified references (fail closed). Not counted when no storage is configured (nothing is verified then). |
| `media_write` | counter | `outcome` = success, validation_failure, conflict, not_found, error | A whole-set media write. `conflict` is a stale version or a duplicate create. |

Alert intent: page on a sustained `storage_error` rate in `media_upload_presign` or `media_verify` (the store is unreachable or
refusing; admins get 503). `missing_object` / `size_mismatch` / `type_mismatch` / `sniff_reject` are client and process
problems: a spike means an uploader is broken or someone is probing; it is not an outage.

### 2.4 Product-card projection

The freshness loop already has `tazzzo.commerce.freshness.*` (requested, attempted, result, failure, completion, reconcile
pass and enqueued, queue lag). Those are not repeated. These are the missing parts:

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `projection_rebuild_queue_depth` | gauge | `state` = due, leased | Product-card rebuild items in the shared `work_queue` (other item types are not counted). `due`: pending plus items whose lease lapsed (a crashed worker). `leased`: held under a live lease. Capped at 100000 per state. |
| `projection_rebuild_queue_oldest_due_age_seconds` | gauge | none | Age of the earliest due item (`requested_at` of a pending item, `lease_until` of a lapsed one); 0 when nothing is due. Unlike `tazzzo.commerce.freshness.queue.lag` (observed only when an item is claimed) it grows when nobody drains the queue. |
| `projection_rebuild_conflicts` | counter | `reason` = delete_race, watermark_cas, create_race, stale_cas | One retry inside a rebuild because a concurrent writer won. Rebuilds re-read every source and converge; this shows contention. |
| `projection_rebuild_failures` | counter | `kind` = non_converged, error | A rebuild threw: gave up after the retry limit, or a source read or write failed. |

The counts walk the existing `(status, type)` index, capped at 100000 per state. The two "oldest" lookups are top-1 ordered
walks of partial indexes created by migration `V0018` (`work_queue (status, requested_at)` and `(status, lease_until)`, partial on
`type = product_card_rebuild`), so they cost a few index keys however large the backlog (`WorkQueueRebuildIndexIT` checks the
plan with `explain`). The counts and each lookup are separate cached snapshots: if one times out only that value goes stale
(the age reads `NaN` until both lookups have succeeded once). The index only exists on a database where `V0018` has been
applied; without it the lookups still work but sort the backlog and may time out. The reconciler is covered by
`tazzzo.commerce.freshness.reconcile.*`; nothing was added.

Alert intent: page when `projection_rebuild_queue_oldest_due_age_seconds` keeps growing past the freshness SLO (consumer
lists are served from the projection) while the freshness `attempted` counter is flat (no worker). Warn on sustained
`projection_rebuild_failures{kind="error"}` and on a high `projection_rebuild_conflicts` rate.

### 2.5 Admin HTTP

Spring's own `http.server.requests` stays **disabled** on purpose (its URI-template-and-exception tag vocabulary is the one
Q5-OBS-1 forbids); the consumer routes have their own meters (section 3). This one covers what they do not.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `admin_http_requests` | timer (count, sum, max) | `surface` = internal, unknown; `route`; `status_class` = 2xx, 3xx, 4xx, 5xx, other | Every internal (`/api/**`, OpenAPI) and unclassified request, around the whole filter chain. |

- `route` is the matched Spring pattern (for example `/api/v1/products/{id}`), a set fixed by the code. A request no handler
  matched (an authentication or body-size refusal, a CORS refusal, a 404 on an unknown path, a scan) is `unmatched`.
  The raw URI is never a tag, and at most 300 distinct routes are ever registered (overflow is `other`).
- Consumer (`/catalog/v1`, `/v1/**` public), customer (`/v1/customer/**`) and health requests are not recorded here.
- The filter runs outermost (`Ordered.HIGHEST_PRECEDENCE`, pinned by `PlatformFilterOrderTest`) so it also counts what the
  later filters answer themselves. It reads the status after the chain returns and changes nothing in the request or response.
  An exception escaping the chain is counted `5xx` and rethrown unchanged.

Alert intent: a 5xx ratio per route above a small threshold for several minutes; a jump of `unmatched` 4xx (scanning or a broken
client; `admin_auth_rejected` says whether it is credentials). Latency: the timer's sum over count per route.

## 3. Existing meters (unchanged, listed so the catalogue is complete)

All are counters unless stated; every tag is a closed enum in its `*Observability` class.

| Family | Meters (tags) |
|---|---|
| Consumer edge | `tazzzo.catalog.consumer.requests` and `.request.duration` (timer) (`route`, `outcome`); `.commerce.failure_class` (`route`, `failure_class`); `.rate_limit.cost` / `.remaining` / `.saturation` (summaries; `route`, `dimension`, `decision`); `.trusted_caller.admissions` (`route`, `caller`, `decision`); `.visibility.probe.duration` (timer) and `.probe.failures` (`route`, `scope`, `result`) |
| Customer rate limit | `customer_rate_limit` (`outcome`, `kind`) |
| Customer auth/session | `session_create_success`, `session_create_failure` (`reason`), `refresh_success`, `refresh_failure` (`reason`), `logout`, `session_auth_rejected` (`reason`), `otp_gateway_send` (`outcome`) |
| Profile, address | `customer_profile_read_success/failure`, `customer_profile_update_success/failure`, `customer_profile_precondition_failed`; `customer_address_*_success`, `customer_address_create_replayed`, `customer_address_failure` (`operation`, `reason`), `address_serviceability_result` (`result`) |
| Cart, checkout | `customer_cart_read_success`, `customer_cart_mutation_success` (`operation`), `customer_cart_failure` (`operation`, `reason`), `customer_cart_item_issue` (`issue`), `cart_expired`; `customer_checkout_quote_success`, `customer_checkout_quote_read_success`, `customer_checkout_failure` (`operation`, `reason`), `customer_checkout_item_rejection` (`reason`) |
| Orders | `order_create_success/failure`, `order_place_cod_success/failure`, `order_cancel_success/failure` (`reason`), `customer_order_read_success`, `customer_order_http_failure` (`operation`, `reason`) |
| Membership, benefits | `membership_operation_success` (`operation`), `membership_failure` (`operation`, `reason`), `membership_transition` (`from`, `to`), `benefits_failure` (`operation`, `reason`) |
| Inventory reservation | `inventory_reservation_success` (`operation`), `inventory_reservation_failure` (`operation`, `reason`), `inventory_reservation_transition` (`from`, `to`), `inventory_reservation_expired` |
| Account deletion | `customer_account_deletion_success` (`outcome`), `customer_account_deletion_failure` (`reason`) |
| Admin | `admin_auth_rejected` (`reason`), `admin_audit_read` (`outcome`) |
| Notifications | `notification_enqueued` (`type`), `notification_dispatch` (`type`, `outcome`), `notification_dispatch_latency` (timer; `type`), gauges `notification_outbox_pending`, `notification_outbox_failed`, `notification_outbox_oldest_pending_age_seconds` |
| Freshness | `tazzzo.commerce.freshness.rebuild.requested`, `.attempted`, `.result` (`result`), `.failure`, `.completion` (`completion`), `.reconcile.enqueued` / `.reconcile.pass` (`pass`), `.queue.lag` (timer) |
| JVM and framework | Whatever Spring Boot's Micrometer auto-configuration registers by default (JVM, process and system binders); no `http.server.*` (disabled by design). Not enumerated here because they are the framework's, not this service's. |

Not instrumented: the database migration runner (it runs as a job and reports in its own exit code and log), the OTP and
notification vendor adapters beyond the meters above (no real vendor exists yet), the Mongo connection pool beyond what
the driver registers on its own.

## 4. Logs

- The console pattern is Spring Boot's default plus `[request_id,correlation_id] ` (property `logging.pattern.correlation`
  in `application.yml`), so every line written while serving a request carries the id the response returns in `X-Request-Id`.
  Outside a request (schedulers, startup) the brackets are empty, for example `[,]`.
- `request_id` is always server-minted. `correlation_id` is the client's `X-Correlation-Id` only when it matches
  `^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$`; anything else is dropped silently, so log injection through it is not possible.
- This is the human-readable layout. There is **no JSON layout** and no log shipper configured in the service; if the
  deployment wants JSON, that is a deployment choice (a Boot structured-logging property or a sidecar) and the MDC keys are
  `request_id` and `correlation_id`.
- Log hygiene is enforced by tests: `LogSafetyGuardTest` rejects, across all production code, a log argument whose name says
  token, authorization, password, secret, phone, e-mail, OTP, session or refresh; `ObservabilityGuardTest` restricts the new
  observability classes to exception class names in log lines; `ObservabilityHttpIT` proves a credential presented to the API
  does not appear in the log output.

## 5. Health

`GET /health/live` and `GET /health/ready` are unauthenticated (they ignore any credential), `no-store`, GET only. The body is
`{"status":"UP|DOWN","components":{"datastore":"OPEN|STARTING|REFUSED|JOB","mongo":"UP|DOWN|SKIPPED","rate_limiter":"UP|DOWN|DISABLED|SKIPPED"}}`:
bounded words only, never a host, port, URI, credential or exception text, including when a dependency fails
(`HealthDisclosureTest` drives failing probes whose exception messages contain a connection string with a password and checks
the body and the log). MongoDB is required for readiness by default; the rate-limiter store (Redis) is reported but optional
(`TAZZZO_HEALTH_REQUIRE_RATE_LIMITER`). A failed probe logs `health_probe_down component=<mongo|rate_limiter> reason=<exception class>`.
See `HTTP_PLATFORM_BASELINE.md` section 1.

## 6. How to scrape: what is decided and what is not

- **Present:** a Micrometer registry bean (Spring Boot Actuator is on the classpath for Micrometer only). Every meter above is
  registered in it. There is **no exporter dependency** (no Prometheus, CloudWatch, OTLP or StatsD registry in the build), so
  by default the meters live in memory and nothing leaves the process.
- **Deliberately not exposed:** `management.endpoints.web.exposure.include` is empty and `/actuator/**` is refused (404) by the
  authentication filter and unknown to `SurfaceClassifier`. This change keeps that. `ConsumerObservabilityIT` asserts it.
- **Pending, deployment-level (not decided here):** the exporter (a Micrometer registry dependency, for example a Prometheus
  scrape endpoint or an OTLP push), where it is exposed (a separate management port bound to the private network is the usual
  shape, never the public listener), and who collects it. Any of these changes the public attack surface or the build, so it
  needs an explicit decision and its own change; nothing in this document should be read as already done.
- **Local inspection today:** run the service with a test profile and read the registry from a test or a debugger; the
  `*IT` classes named above show how.
- **Before wiring a collector:** every meter name above is final; counters are per instance (aggregate across instances in the
  collector); gauges are per instance too, and several instances report the same shared queue or collection, so aggregate
  gauges with max, not sum.

## 7. Not verified

- No exporter or dashboard exists, so none of the alert intents above has been exercised against a real collector.
- The two "oldest" lookups on `work_queue` are proven index-only by an `explain` test on a 8,500-row queue, not timed against a production-sized (million-row) backlog.
- Gauge refresh behaviour under multiple instances sharing one database is by construction (per-instance cache), not measured.
- Metric values after a process restart (counters reset) are standard Micrometer behaviour, not tested here.
