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
- **Metric.** `notification_dispatch{type, outcome=sent|retry|failed|rejected|expired|claim_lost}`. Logs carry the type, outcome and attempt only.

## Configuration
| Property | Default | Meaning |
|---|---|---|
| `tazzzo.scheduler.enabled` + `tazzzo.scheduler.notification-dispatch-enabled` | false | both must be true to run the dispatcher |
| `tazzzo.notifications.dispatch-ms` | 5000 | tick delay |
| `tazzzo.notifications.batch-size` | 50 | rows per tick (1..500) |
| `tazzzo.notifications.lease-seconds` | 60 | claim lease |
| `tazzzo.notifications.max-age-seconds` | 3600 | older rows expire unsent |
| `tazzzo.notifications.base-backoff-seconds` | 30 | first retry delay |
| `tazzzo.notifications.max-attempts` | 5 | 1..20 |

## Not done: external decisions (UNVERIFIED)
- **Provider.** There is no `NotificationSender` adapter yet. The choice is DLT-registered SMS templates, a WhatsApp BSP or FCM push, and it needs credentials, template ids and sender ids. The default `DisabledNotificationSender` makes **enabling dispatch a startup failure** rather than a silent drop. Until an adapter lands, rows stay PENDING and are removed by TTL.
