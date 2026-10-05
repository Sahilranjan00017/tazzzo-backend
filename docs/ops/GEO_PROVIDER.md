# Geo provider (lat/lng → PIN)

## Contract
`com.tazzzo.location.GeoPincodeResolver` is the only seam to a reverse-geocoding provider:
`enabled()` and `Optional<String> resolve(GeoPoint)`. It returns a 6-digit PIN string or empty; an outage is
`GeoProviderUnavailableException`. Serviceability is always decided from the returned PIN by the existing serviceability
domain — the provider never decides serviceability.

## Behaviour
| Situation | Result |
|---|---|
| No provider configured (default `DisabledGeoPincodeResolver`) | `lat`/`lng` → 400 `INVALID_REQUEST`, exactly as before |
| Provider enabled, `lat`+`lng` valid, PIN found | normal serviceability answer for that PIN |
| Provider enabled, no PIN for the point, or provider returns a non-PIN | 200 `serviceable:false` (outside coverage) |
| `lat` without `lng`, non-numeric, NaN/Infinity, out of range, or together with `pin` | 400, provider never called |
| Provider outage/timeout | 503 `UNAVAILABLE` (never a guess) |
| `GET /v1/categories/{id}/products`, `GET /v1/products/{id}` | PIN-only; `lat`/`lng` is always 400 |

The provider is invoked only AFTER consumer admission is charged, so unauthenticated floods hit the rate limiter first.
Coordinates are never persisted, logged, or echoed.

## External gate (UNVERIFIED)
No concrete provider ships in this repository. To enable one, a provider module must contribute a `GeoPincodeResolver`
bean (the default is `@ConditionalOnMissingBean`) with a bounded timeout, a circuit breaker, and its credential supplied
by the secret store (never in git). Provider choice, pricing/quota, data-residency review and a staging verification
against the real provider are business/external decisions and remain open.
