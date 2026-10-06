# Customer-surface rate limits

`CustomerRateLimitFilter` runs on `/v1/customer/**` AFTER `CustomerAuthFilter`. Behaviour:

- **Bucket key.** The key is the verified customer id (`rl:customer:read:<id>` / `rl:customer:write:<id>`). It is never a header or body value the client chose.
- **Unauthenticated requests.** They are answered 401 by the auth filter and spend nothing.
- **Reads vs writes.** GET/HEAD charge the read bucket; every other method charges the write bucket. A polling burst therefore cannot starve checkout.
- **Refusal.** `429 RATE_LIMITED` with `Retry-After` (whole seconds, at least 1) and `Cache-Control: no-store`, in the flat customer error envelope `{code, message, requestId}`. The body never carries the customer or session id.
- **Store outage.** Redis unreachable or throwing **fails closed**: `503 SERVICE_UNAVAILABLE`.
- **Metric.** `customer_rate_limit{outcome=allowed|limited|unavailable, kind=read|write}`. It has bounded tags and no identifiers.

## Configuration

| Property | Meaning |
|---|---|
| `tazzzo.customer-rate-limit.reads.capacity` / `.refill-per-second` | read bucket |
| `tazzzo.customer-rate-limit.writes.capacity` / `.refill-per-second` | write bucket |

- **All unset:** not enforced. Startup logs `customer_rate_limit_not_enforced`.
- **Partially set, non-positive, or capacity above 1,000,000:** startup failure.
- **Set without a store:** also a startup failure. The store exists only with `tazzzo.consumer-rate-limit.mode=REDIS`, the same Valkey/Redis as the public consumer and OTP limits.

**Values are a deployment decision (UNVERIFIED).** No production numbers are chosen in code. A starting point to load-test is reads 120 / 2 per second and writes 30 / 0.5 per second.

## Not covered here
- **Public surfaces:** `/v1/catalog`, `/v1/search`, content and app-config keep the IP/installation admission (`ConsumerRateLimiter`).
- **OTP:** keeps its phone/challenge buckets (`OtpRateLimiter`).
- **Admin/staff API:** it is behind service tokens and Google identity and is not customer-budgeted.
