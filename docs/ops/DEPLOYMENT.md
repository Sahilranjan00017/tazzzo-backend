# Deploying catalog-service

**Nothing in this repository deploys anything.** This document describes the artifact and the contract a platform must meet. Every external gate at the end is **UNVERIFIED** until someone with the authority runs it.

## Artifact
- **Image.** `services/catalog-service/Dockerfile` packages the Boot jar as layers on a digest-pinned `eclipse-temurin:21-jre-jammy`.
  - **User:** uid/gid `10001`.
  - **Contents:** no configuration and no secrets.
  - **Build context:** `.dockerignore` admits only `target/catalog-service-*.jar`.
  - **JVM:** `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`.
- **Build.** `./mvnw -B -DskipTests package && docker build -t tazzzo-catalog-service:<git-sha> services/catalog-service`.
- **CI.** The `image` job in `backend-ci.yml` builds the image on every PR and checks the user and that no configuration is baked in. It **never pushes**: choosing a registry is a platform decision.
- **Local proof (2026-10-05)**, against a throwaway `mongo:7` single-node replica set:
  - the image ran as the migration job and applied V0001–V0007 (exit 0);
  - it then ran as the runtime on a **read-only root filesystem with tmpfs `/tmp`**;
  - it refused a non-local URI without TLS/credentials;
  - it refused an unmigrated database;
  - it answered 401 without a token;
  - it drained gracefully on SIGTERM.

## One image, three roles
| Role | How it runs | Key settings |
|---|---|---|
| **Migration job** | One-shot before a rollout, with the **migrator** identity | `TAZZZO_MIGRATION_MODE=DRY_RUN`, then `APPLY` with the two-key confirmation (`TAZZZO_MIGRATION_CONFIRM_DATABASE`, `…_CONFIRM_ENVIRONMENT`, `…_OPERATOR`), plus `TAZZZO_MIGRATION_EXIT_AFTER_RUN=true`. See `docs/database/DATABASE_MIGRATION_RUNBOOK.md` and `DATABASE_STAGING_RUNBOOK.md`. **APPLY outside local/test needs explicit human authorisation.** |
| **API** | N replicas behind the load balancer, **runtime** identity | `TAZZZO_MIGRATION_MODE=VERIFY` (default): it refuses to start on an unmigrated or drifted schema. `TAZZZO_SCHEDULER_ENABLED=false`. |
| **Worker** | Exactly one replica, runtime identity, not behind the load balancer | `TAZZZO_SCHEDULER_ENABLED=true` plus the subordinate worker flags it should run (card projection, reservation expiry, notification dispatch once a provider exists). Workers are idempotent and leased, but one replica keeps load predictable. |

## Environment

### Secrets
Inject these from the platform secret store. Never put them in an image, a file in the repo, or a log. Each `*_B64` key is base64 of at least 32 random bytes, generated per environment, and no two keys may be equal.
- `MONGODB_URI`: SRV or TLS, with credentials, and the runtime and migrator identities kept distinct.
- `TAZZZO_CMS_TOKEN`, `TAZZZO_READ_TOKEN`.
- `TAZZZO_CONSUMER_CURSOR_HMAC_KEY_B64`.
- `TAZZZO_CUSTOMER_AUTH_ACCESS_TOKEN_HMAC_KEY_B64` (and `…_PREVIOUS_…_KEYS_B64` during a rotation).
- `TAZZZO_CUSTOMER_SESSION_REFRESH_HMAC_KEY_B64`.
- `TAZZZO_CUSTOMER_AUTH_OTP_HMAC_KEY_B64`.
- `TAZZZO_RATE_LIMIT_REDIS_URL`: TLS (`rediss://`) to the managed Valkey/Redis.
- **Trusted storefront caller (optional):** `TAZZZO_CONSUMER_TRUSTEDCALLERS_0_NAME` (e.g. `storefront`) and `TAZZZO_CONSUMER_TRUSTEDCALLERS_0_SECRET` (32..256 printable ASCII, e.g. `openssl rand -base64 48`). The storefront's server sends the same pair as `X-Tazzzo-Caller` / `X-Tazzzo-Caller-Secret` and is then admitted on its own bucket (`TAZZZO_RATE_LIMIT_CALLER_CAPACITY` / `…_REFILL`, defaults 20000 / 2000 per second, not load-tested). Rotation is zero-downtime (below). A mismatch is never refused; it only falls back to the shared IP bucket, with one WARN a minute.
- **OTP SMS gateway credentials:** `docs/ops/OTP_GATEWAY.md` (#65).

### Configuration
- `MONGODB_DATABASE`, `TAZZZO_MIGRATION_ENVIRONMENT` (`staging` | `production`), `TAZZZO_BUILD_VERSION` (the git SHA).
- **Admin Google sign-in:** `TAZZZO_ADMIN_OIDC_ISSUER`, `…_AUDIENCE`, `…_HOSTED_DOMAIN`, `…_CREDENTIAL_LABEL`.
- **Public rate limits:** `TAZZZO_CONSUMER_RATE_LIMIT_MODE=REDIS`, `TAZZZO_TRUSTED_PROXY_CIDRS` (exactly the load balancer's ranges), and the IP/installation budgets. With `DISABLED`, every public read fails closed with 503, which is what the local proof showed.
- **OTP:** `TAZZZO_OTP_PROVIDER_MODE` and its per-IP/phone/challenge budgets.
- **Shutdown:** `TAZZZO_SHUTDOWN_GRACE` (default `25s`). Keep it below the orchestrator's kill timeout (commonly 30 s).
- **Unmerged PRs** add their own keys: customer rate limits (#69), notifications (N2), geo/media providers (#57, #62) and the HTTP platform limits (#55). Their docs list them.

### Trusted storefront caller
- **Rotation (zero downtime).** Each caller accepts up to two secrets: `…_SECRET` and an optional `TAZZZO_CONSUMER_TRUSTEDCALLERS_0_PREVIOUSSECRET`. Both are validated the same way and must differ, and neither is ever logged.
  1. **Add new as previous.** Set the new secret as `…_PREVIOUSSECRET` on the backend; `…_SECRET` stays the old one. Roll the backend. Both secrets are now accepted.
  2. **Roll the storefront** to send the new secret. Wait until no instance still sends the old one: the `trusted_caller_rejected caller=storefront` WARN stays silent and `admissions{decision="allowed"}` keeps flowing.
  3. **Promote.** On the backend, set `…_SECRET` to the new secret and `…_PREVIOUSSECRET` to the old one. Roll the backend.
  4. **Remove old.** Unset `…_PREVIOUSSECRET`. Roll the backend. Only the new secret is accepted.
- **Budget.** The caller-bucket defaults (`TAZZZO_RATE_LIMIT_CALLER_CAPACITY=20000`, `…_REFILL=2000`) are not load-tested. Size them in staging against real storefront traffic, together with the IP budgets. Capacity must stay at or above the costliest single request (a root listing), or that request answers 503.
- **Alert.** Alert on `tazzzo.catalog.consumer.trusted_caller.admissions{decision="rate_limited"}`: a sustained non-zero rate means the whole storefront is being throttled. Also watch `decision="unavailable"` (limiter store down, or a request costing more than capacity) and the `trusted_caller_rejected` WARN (a misconfigured secret, or a mid-rotation storefront).
- **Network.** Make the backend reachable only from private networks: a security group or source-CIDR allow-list admitting the load balancer and the storefront's egress, never the public internet directly. A leaked caller secret lets its holder bypass the per-IP limits up to the whole caller budget, so restricting who can reach the backend at all is the backstop. Rotate immediately on suspected leakage.

## Container contract
- **Port:** 8080 (HTTP). TLS terminates at the load balancer.
- **Security context:** read-only root filesystem, writable `/tmp` (tmpfs), `runAsNonRoot`, no added capabilities, no privilege escalation.
- **Probes:** liveness `GET /health/live`, readiness `GET /health/ready`. Both arrive with #55; until it merges there is no HTTP health endpoint, and Actuator is deliberately not exposed. Readiness must gate traffic, because the process refuses requests until the datastore and migrations verify.
- **Shutdown:** on SIGTERM, Tomcat stops accepting work and drains in-flight requests up to `TAZZZO_SHUTDOWN_GRACE`. The JVM then exits with 143 (the normal SIGTERM code).
- **Resources:** start with 1 vCPU and 1–1.5 GiB of memory per API replica and size from load tests. The heap follows the limit through `MaxRAMPercentage`.
- **Logs:** stdout. They carry request ids and never tokens, Authorization headers, session ids, phone numbers or emails.

## Rollout order
1. Build the image at the release commit, then push it to the registry (platform decision).
2. Run the migration job: `DRY_RUN`, review, then `APPLY` with two-key confirmation. **Requires authorisation.**
3. Roll the API replicas. VERIFY mode refuses to start against a schema it does not match.
4. Roll the worker.

**Rollback:** redeploy the previous image. Migrations are append-only (index creation), and nothing in V0001–V0007 is destructive.

## External gates: all UNVERIFIED
- [ ] Container registry chosen and image pushed
- [ ] Atlas cluster with TLS; runtime and migrator users created from `docs/database/roles/*.role.json`
- [ ] Managed Valkey/Redis with TLS reachable from the API
- [ ] Secrets created in the platform secret store
- [ ] Load balancer with TLS, trusted-proxy CIDRs recorded
- [ ] Staging migration DRY_RUN reviewed, APPLY authorised and run
- [ ] OTP SMS provider (DLT templates) live; notification provider chosen
- [ ] Production rate-limit budgets load-tested
- [ ] Payment provider: see the payment readiness report (COD only today)
