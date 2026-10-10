# Customer notifications: transactional outbox

## What exists
- **Enqueue.** A business flow calls `NotificationEnqueuer.enqueue(session, request)` inside its own Mongo transaction.
  - The row in `notification_outbox` commits or rolls back with the business write.
  - `_id` is the dedupe key `TYPE:subject`, so a retried transaction or an idempotent replay never enqueues twice. The write is an upsert with `$setOnInsert`, so a repeat never raises a duplicate-key error that would abort the caller.
  - **Wired today**, each inside the authoritative business transaction:

| Type | Enqueued by | Subject (dedupe) | Params |
|---|---|---|---|
| `ORDER_CONFIRMED` | COD placement | order id | `item_count`, `payable_paise` |
| `ORDER_OUT_FOR_DELIVERY` | staff transition | order id | — |
| `ORDER_DELIVERED` | staff transition | order id | — |
| `ORDER_CANCELLED` | customer cancel or staff cancel (an order is cancelled at most once) | order id | `cancelled_by` (CUSTOMER/STAFF), `reason_code` (closed set) |
| `SUPPORT_REPLY` | staff reply on a case | `<caseId>-m<message id>` | `category` |
| `SUPPORT_CASE_RESOLVED` | staff resolves a case | `<caseId>-v<case version>` (a reopened case can be resolved again) | `category` |

  - A refused, stale or invalid transition rolls back and enqueues nothing; an idempotent replay (a second customer cancel) returns before enqueueing, and the dedupe key would absorb it anyway. Message text, addresses and staff identities are never in the outbox.
  - **Deliberately not notified:** a customer's own actions that the app already shows (opening a case, replying, closing, placing is covered by ORDER_CONFIRMED); account events (OTP sign-in is its own SMS; an account deletion erases the outbox, so nothing can be sent to an erased account); marketing (out of scope).
- **No contact data is stored.** The recipient is the opaque `customer_id`. The provider adapter resolves the phone number or push token at send time. Params are bounded, non-PII template values.
- **Dispatch.** `NotificationDispatcher` runs on a schedule:
  - **Claim:** it atomically claims the oldest due row (PENDING and due, or SENDING with a lapsed lease) and stamps a fresh claim token.
  - **Token-conditional completion:** every completion must present that token, so a worker whose lease lapsed can never overwrite the result of the worker that took over.
  - **Outcomes:**

| Sender result | Row becomes |
|---|---|
| `SENT` | `SENT` |
| `REJECTED` (no contact, opted out, template refused) | `FAILED`, never retried |
| `RETRY` or an exception | `PENDING` again, with backoff `base × 2^(attempt-1)` capped at 30 min; `FAILED` after `max-attempts` |
| row older than `max-age-seconds` | `EXPIRED`, never sent (a confirmation an hour late is noise) |

- **Retention.** Every row carries `expire_at` (creation + `NotificationOutbox.RETENTION`). The TTL index `notification_expiry_ttl` removes it whatever its state. The value is a code default pending the production retention policy.
- **Erasure.** `NotificationErasure` deletes every row of the customer, whatever its state, inside the account-deletion transaction (`AccountDeletionService`).
- **Metrics** (tags are only the closed `NotificationType` enum / outcome set; no ids):

| Meter | Kind | Meaning |
|---|---|---|
| `notification_enqueued{type}` | counter | a NEW outbox row was written (deduped repeats are not counted). Recorded inside the caller's transaction, so a transaction that later rolls back can leave it one too high |
| `notification_dispatch{type, outcome=sent\|retry\|failed\|rejected\|expired\|claim_lost}` | counter | dispatcher outcomes |
| `notification_dispatch_latency{type}` | timer | enqueue to provider-accepted, recorded on `sent` only |
| `notification_outbox_pending` | gauge | PENDING rows plus SENDING rows whose lease has lapsed (a crashed dispatcher; due for re-claim). Each count capped at 100000 |
| `notification_outbox_failed` | gauge | terminal FAILED rows still within the 7-day retention (same cap) |
| `notification_outbox_oldest_pending_age_seconds` | gauge | now minus the earliest due time (`next_attempt_at` of PENDING, `lease_until` of lapsed SENDING), floored at 0; 0 when none. A row waiting out a backoff is not "behind". With dispatch off (today) it is simply the age of the oldest queued row |

  The three gauges read one snapshot refreshed at most every `metrics-refresh-seconds` (default 15): a few bounded queries per interval (each capped at 100000 rows and 2 s `maxTime`) however often Prometheus scrapes. The refresh runs outside any lock. Any error (Mongo or otherwise) keeps the last good snapshot (NaN before the first) and the next attempt waits a full interval.
  **Limitation:** there is no index on `lease_until` and none was added. PENDING and FAILED use the `status` prefix of `notification_due`; the lapsed-SENDING queries walk the `status=SENDING` prefix and filter `lease_until` on the in-flight rows (normally at most `batch-size`), bounded by the cap and `maxTime`. A very large SENDING backlog would make that refresh slower, not unbounded. Logs carry the type, outcome and attempt only: never the customer id, subject, params or message text.
- **Admin view.** `GET /api/v1/admin/dashboard/summary` already returns `notifications.pending` and `notifications.failed` (the only data the CMS notifications module consumes). Nothing was added: per-message detail would expose recipients or payloads and is deliberately not offered.

## Configuration
| Property | Default | Meaning |
|---|---|---|
| `tazzzo.scheduler.enabled` + `tazzzo.scheduler.notification-dispatch-enabled` | false | both must be true to run the dispatcher |
| `tazzzo.notifications.provider` (`TAZZZO_NOTIFICATIONS_PROVIDER`) | `disabled` | exactly `disabled` or `sandbox` (lowercase, as with the OTP provider-mode; `SANDBOX` fails startup); blank means `disabled`. The environment value is trimmed |
| `tazzzo.notifications.dispatch-ms` | 5000 | tick delay |
| `tazzzo.notifications.batch-size` | 50 | rows per tick (1..500) |
| `tazzzo.notifications.lease-seconds` | 60 | claim lease |
| `tazzzo.notifications.max-age-seconds` | 3600 | older rows expire unsent |
| `tazzzo.notifications.base-backoff-seconds` | 30 | first retry delay |
| `tazzzo.notifications.max-attempts` | 5 | 1..20 |
| `tazzzo.notifications.metrics-refresh-seconds` | 15 | gauge snapshot cache, 1..3600 |

Every key has an env override in `application.yml` (`TAZZZO_NOTIFICATIONS_<KEY>`, `TAZZZO_NOTIFICATION_DISPATCH_ENABLED` for the dispatch flag).

## Sandbox sender (dev/test only, no vendor)
`TAZZZO_NOTIFICATIONS_PROVIDER=sandbox` plus `TAZZZO_SCHEDULER_ENABLED=true` and `TAZZZO_NOTIFICATION_DISPATCH_ENABLED=true` runs the whole lifecycle with no credentials and no network I/O: rows go PENDING, SENDING, SENT. The sender keeps the last 100 `(id, attempt, outcome)` in memory only and logs type, attempt and outcome.
- **It reaches nobody.** It marks customer messages SENT that no one receives, so startup refuses it unless `tazzzo.migration.environment` is unset, `local`, `test` or `dev` (same rule as the OTP `LOGGING` provider). Never use it to test a real environment.
- It always answers SENT. Failure injection (RETRY, REJECTED, exceptions) exists only as a test subclass; there is no property for it.
- With `provider=disabled`, enabling dispatch is still a startup failure.

## Runbook
- **Pending grows / oldest-pending age climbs:** dispatch is off (expected today: `provider=disabled`), the dispatcher is failing (`notification_dispatch_tick_failed` warning), or the provider is throttling (`retry` outcomes). Rows older than `max-age-seconds` become EXPIRED unsent, and all rows vanish at 7 days.
- **Failed grows:** `rejected` (permanent: no contact, opted out, template) or `failed` (max attempts exhausted). FAILED is terminal; there is no automatic replay.
- **Gauge is NaN:** no successful snapshot yet (database unreachable since start).

## Not done: external decisions (UNVERIFIED)
- **EXTERNAL_CONFIG_PENDING: Provider.** There is no vendor `NotificationSender` adapter yet; only the dev-only sandbox exists. The choice is DLT-registered SMS templates, a WhatsApp BSP or FCM push, and it needs credentials, template ids and sender ids. The default `DisabledNotificationSender` makes **enabling dispatch a startup failure** rather than a silent drop. Until an adapter lands, rows stay PENDING and are removed by TTL.
