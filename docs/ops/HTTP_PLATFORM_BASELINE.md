# HTTP platform baseline — tazzzo-backend

What the service itself guarantees at the HTTP layer, independent of any edge (ALB, WAF, gateway). Everything here is
enforced in code and pinned by tests; nothing depends on infrastructure that does not exist yet.

## 1. Health probes

| Path | Purpose | Answers | Depends on |
|---|---|---|---|
| `GET /health/live` | container runtime health check (ECS `healthCheck`) | always `200 {"status":"UP",…}` while the process serves HTTP | nothing: a dependency outage can never make the orchestrator restart every instance |
| `GET /health/ready` | load-balancer target health (ALB target group) | `200` when the instance may take traffic, else `503` with the same body shape | the datastore serving gate (`OPEN`), a bounded MongoDB `ping` (required by default), the rate-limiter store (reported; optional) |

- Surface `HEALTH` in `SurfaceClassifier`: **exact** paths, unauthenticated, no rate limiting, `Cache-Control: no-store`.
  `/health`, `/health/`, `/healthz`, `/health/livex`, `/actuator/**` stay UNKNOWN (404).
- Body: `{"status":"UP|DOWN","components":{"datastore":"OPEN|STARTING|REFUSED|JOB","mongo":"UP|DOWN|SKIPPED","rate_limiter":"UP|DOWN|DISABLED|SKIPPED"}}`.
  Bounded words only: never a host, port, URI, user, version, exception or stack trace (the path is unauthenticated).
- `datastore` is `DatastoreReadiness`: `OPEN` only after the DB-4 verifier passed and startup completed in a serving mode.
  A process the verifier refused (`REFUSED`) or a migration job (`JOB`) is never ready, so a job container never receives traffic.
- Probes are cached for 1 s (several probers cannot become a ping storm) and each dependency probe is bounded to 2 s on
  a dedicated thread (a hung dependency cannot hang the probe; it reports `DOWN`).
- `TAZZZO_HEALTH_REQUIRE_MONGO` (default `true`), `TAZZZO_HEALTH_REQUIRE_RATE_LIMITER` (default `false`). The limiter store
  is optional for readiness on purpose: the limiter already fails closed (503) when the store is down, and failing every
  target's readiness at once would make ECS replace tasks, which cannot fix a shared store. `DISABLED` mode is reported, never a failure.

ECS/ALB wiring (infrastructure track): container health check → `/health/live`; target-group health check → `/health/ready`,
healthy threshold ≥ 2, interval 10–15 s, timeout ≤ 5 s; `server.shutdown=graceful` with a 25 s phase, so set the ECS stop
timeout and the target-group deregistration delay to ≥ 30 s.

## 2. Request-body size limit

Every request body on every surface is bounded by `tazzzo.http.max-request-body-bytes` (`TAZZZO_HTTP_MAX_BODY_BYTES`,
default 65536; allowed 1 KiB–16 MiB, startup failure outside). `RequestBodyLimitFilter` runs right after the request-id
filter, **before authentication**:

- a declared `Content-Length` above the limit → `413 PAYLOAD_TOO_LARGE` without reading the body;
- a body with no declared length (chunked) → read into a buffer bounded by the limit; one byte past it → `413`;
- the 413 uses the surface's envelope (nested on `/api/**`, flat elsewhere) and `Connection: close`;
  `server.tomcat.max-swallow-size` equals the limit so the container stops draining what was refused.
- `server.max-http-request-header-size` is 16 KB.

## 3. Error sanitisation

`ApiExceptionHandler` (every surface) never echoes framework text: an unreadable body is
`400 MALFORMED_REQUEST "request body is malformed or unreadable"`; a type-mismatched parameter names the parameter only;
a missing/unsatisfied request parameter is `400 MALFORMED_REQUEST` naming the parameter condition (this closes the
`GET /api/v1/products` without `canonicalKey` → 500 defect at the framework level; a proper admin list endpoint is
separate work); `406 NOT_ACCEPTABLE`; the catch-all 500 stays `"internal error"` with the stack trace logged server-side only.
Public, customer and health surfaces keep the flat envelope with the collapsed public vocabulary.

## 4. Request and correlation ids

`X-Request-Id` is always server-minted (`req_` + 20 hex). An inbound `X-Correlation-Id` is accepted only when it matches
`^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$`; anything else (CR/LF, spaces, control characters, over-length, braces…) is dropped
silently — never logged, never echoed. A valid one is put in MDC (`correlation_id`) and echoed back.

## 5. CORS

OFF unless `TAZZZO_HTTP_CORS_ALLOWED_ORIGINS` lists exact origins (comma-separated). Rules: exact `scheme://host[:port]`,
`https` required (`http` only for `localhost` / `127.0.0.1`), no wildcard, no path/userinfo/query; invalid entries fail
startup. When on: those origins only (never reflected), methods `GET POST PUT PATCH DELETE OPTIONS`, request headers
`Authorization Content-Type Accept If-Match If-None-Match Idempotency-Key X-Correlation-Id X-Installation-Id`, exposed
`X-Request-Id X-Correlation-Id ETag Retry-After Location`, `Access-Control-Allow-Credentials: false` (bearer tokens travel in
the header, never cookies), max-age 600 s. The CORS filter runs before both auth filters, so a pre-flight never meets a 401;
**any request carrying an `Origin` outside the allowlist is refused with a plain 403** (a non-browser client does not send
`Origin`). The mobile app and a server-side BFF are not subject to CORS; a browser CMS talks to a BFF, or its origin is listed here.

## 6. Forwarded headers / trusted proxies

`server.forward-headers-strategy=none`: the application never rewrites scheme, host, port or client address from
`X-Forwarded-*`, and never builds an absolute URL from a request. The one consumer of `X-Forwarded-For` is the rate limiter's
`ClientIpResolver`, which honours it only when the direct peer is inside `TAZZZO_TRUSTED_PROXY_CIDRS` and fails closed
(503) otherwise. Behind the ALB that list MUST contain the ALB subnets.

## 7. Filter chain (a security boundary; pinned by `PlatformFilterOrderTest`)

`RequestIdFilter` → `RequestBodyLimitFilter` → CORS → `ApiAuthFilter` (service token / OIDC on `/api/**`) → `CustomerAuthFilter` (`/v1/customer/**`).

## 8. Graceful shutdown

`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=25s`: on SIGTERM the connector stops accepting,
in-flight requests complete (up to 25 s), then the context closes (scheduled workers stop with it).
