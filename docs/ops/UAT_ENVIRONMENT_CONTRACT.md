# UAT environment contract (backend)

One table of every setting the backend reads that matters in UAT: name, whether it is required, default, whether it is a secret, where the value comes from and who sets it. It is **derived from the code** at `main` `ace278c` (`application.yml`, every `@ConfigurationProperties` class, every `@Value`), and cross-checked against the infrastructure branch `feature/uat-1-aws-uat-foundation` at `34b02a0` (infra PR #21, not merged): `terraform/uat/files/{compose.yml,common.sh,deploy.sh,nginx.conf.template}`, `terraform/uat/templates/uat.env.tftpl` and `docs/uat/UAT_SECRETS_NAMES.md`.

Status: **static analysis only.** Nothing was started, no AWS call was made, no UAT host was contacted, and no Spring context was booted to confirm a binding. Statements about what the backend does are read from the source named next to them.

`scripts/uat-env-contract-check.sh` fails if an environment variable of `application.yml` is missing from this file, so the table cannot silently drift.

## 1. How a value reaches the process

| Step | Fact (source) |
|---|---|
| SSM to env | `deploy.sh` reads `/tazzzo/uat/backend/` with the instance role. The **last path segment is the variable name**; only segments matching `^[A-Z][A-Z0-9_]*$` go to the backend (`common.sh`, `build_env_files`). |
| Not forwarded | `VALKEY_PASSWORD` goes to the Valkey container, `ORIGIN_VERIFY_SECRET` to the nginx origin guard. The backend never receives them. |
| Derived, never set by hand | `TAZZZO_RATE_LIMIT_REDIS_URL`, `TAZZZO_TRUSTED_PROXY_CIDRS`, `TAZZZO_BUILD_VERSION` (a parameter with such a name is ignored). |
| Value alphabet | Printable ASCII without `'` and `\` (the whole deploy is refused otherwise). |
| Pre-check | `deploy.sh` only checks that 12 names exist (`REQUIRED_PARAMS`, section 4). The backend validates the rest at startup or at request time. |
| Name mapping | `application.yml` spells most names out (`${TAZZZO_...:default}`). For a property with **no placeholder** Spring Boot's relaxed binding applies: upper-case, dots become `_`, and for `@ConfigurationProperties` **dashes are removed** (`tazzzo.customer-auth.otp.http.auth-header-value` is `TAZZZO_CUSTOMERAUTH_OTP_HTTP_AUTHHEADERVALUE`). A key read with `@Value` also accepts the underscore spelling (`tazzzo.checkout.delivery-slot-required` is `TAZZZO_CHECKOUT_DELIVERY_SLOT_REQUIRED`). List items take an index (`..._TRUSTEDCALLERS_0_SECRET`). |

Legend. **Req**: R = the service does not start, or the E2E path cannot work, without it; S = needed for a named UAT feature; O = optional. **Secret**: Y = SecureString (never in chat, logs or git), N = plain String. **From**: `SSM-SS` / `SSM-S` = SecureString / String under `/tazzzo/uat/backend/<NAME>`; `derived` = `deploy.sh`; `image` = baked default. **Who**: the person who creates the parameter (UAT operator = the owner).

## 2. Settings needed in UAT

### 2.1 Datastore and environment identity

| Name | Req | Default | Secret | From | Who | Effect / UAT value |
|---|---|---|---|---|---|---|
| `MONGODB_URI` | R | `mongodb://localhost:27017/tazzzo?replicaSet=rs0` | Y | SSM-SS | operator | Atlas SRV/TLS URI of the **runtime** user. With `TAZZZO_MIGRATION_ENVIRONMENT=staging` the startup verifier enforces the full connection contract (`DatastoreStartupVerifier`) and the runtime privilege profile. The migrator URI lives in `/tazzzo/uat/migration/MONGODB_URI`, which the host cannot read. |
| `MONGODB_DATABASE` | R | `tazzzo` | N | SSM-S | operator | `tazzzo_uat`. The default would write to the database called `tazzzo`. |
| `TAZZZO_MIGRATION_ENVIRONMENT` | R | empty | N | SSM-S | operator | `staging` (one of `local test dev staging production`). Empty with a non-loopback URI is a startup failure (`ENVIRONMENT_NOT_IDENTIFIED`). **`staging` also forbids OTP provider `LOGGING` (section 5).** |
| `TAZZZO_MIGRATION_MODE` | O | `VERIFY` | N | image default | none | Keep `VERIFY` for the API: it refuses an unmigrated or drifted schema. `DRY_RUN` / `APPLY` belong to the one-shot migration job only. |
| `TAZZZO_MIGRATION_CONFIRM_DATABASE`, `TAZZZO_MIGRATION_CONFIRM_ENVIRONMENT`, `TAZZZO_MIGRATION_OPERATOR`, `TAZZZO_MIGRATION_EXIT_AFTER_RUN` | O | empty / empty / empty / `false` | N | migration job only | operator | Two-key confirmation for `APPLY` outside local/test/dev (`DATABASE_MIGRATION_RUNBOOK.md`). Never on the API container. |
| `TAZZZO_DATASTORE_PRIVILEGE_VERIFICATION` | O | `AUTO` | N | image default | none | `AUTO` enforces for `staging`/`production`. There is no value that switches enforcement off there. |
| `TAZZZO_BUILD_VERSION` | derived | `unknown` | N | derived | `deploy.sh` | The deployed git SHA. |

### 2.2 Rate limiting and proxy (the consumer surface fails closed without these)

| Name | Req | Default | Secret | From | Who | Effect / UAT value |
|---|---|---|---|---|---|---|
| `TAZZZO_CONSUMER_RATE_LIMIT_MODE` | R | none (startup failure) | N | SSM-S | operator | `REDIS`. `DISABLED` makes every public read answer 503. Case-sensitive. |
| `TAZZZO_RATE_LIMIT_REDIS_URL` | R | empty | Y (carries the password) | derived | `deploy.sh` | `redis://:<VALKEY_PASSWORD>@valkey:6379`. Plain `redis://` is accepted by the parser (`rediss://` switches TLS on). |
| `TAZZZO_TRUSTED_PROXY_CIDRS` | R | empty | N | derived | `deploy.sh` | The compose subnet. REDIS mode refuses an empty list. nginx forwards `X-Forwarded-For` verbatim, so the client address is the one CloudFront appended. |
| `TAZZZO_RATE_LIMIT_IP_CAPACITY`, `TAZZZO_RATE_LIMIT_IP_REFILL` | R | `0` | N | SSM-S | operator | **Must be > 0** (REDIS mode: startup failure otherwise). Capacity at least the costliest request (a 50-id batch costs 51). |
| `TAZZZO_RATE_LIMIT_INSTALL_CAPACITY`, `TAZZZO_RATE_LIMIT_INSTALL_REFILL` | R | `0` | N | SSM-S | operator | Same rule. |
| `TAZZZO_RATE_LIMIT_CALLER_CAPACITY`, `TAZZZO_RATE_LIMIT_CALLER_REFILL` | O | `20000`, `2000` | N | image default | none | Bucket of the trusted storefront caller; not load-tested. |
| `TAZZZO_CONSUMER_TRUSTEDCALLERS_0_NAME` | O | none | N | SSM-S | operator | `storefront` (`[a-z][a-z_]{0,19}`). Set together with the secret. |
| `TAZZZO_CONSUMER_TRUSTEDCALLERS_0_SECRET` | O | none | Y | SSM-SS | operator | 32..256 printable ASCII; the **same value** is the storefront's `TAZZZO_CALLER_SECRET`. `..._0_PREVIOUSSECRET` only while rotating. |
| `TAZZZO_CUSTOMERRATELIMIT_READS_CAPACITY`, `..._READS_REFILLPERSECOND`, `..._WRITES_CAPACITY`, `..._WRITES_REFILLPERSECOND` | S | all unset = **not enforced** (WARN `customer_rate_limit_not_enforced`) | N | SSM-S | operator | `tazzzo.customer-rate-limit.{reads,writes}.{capacity,refill-per-second}`, no placeholder in `application.yml`. All four positive or all four unset; set without REDIS mode is a startup failure. Starting point in `CUSTOMER_RATE_LIMITS.md`: reads 120 / 2 per second, writes 30 / 0.5. The names here follow the relaxed-binding rule and were **not** run. |
| `TAZZZO_HEALTH_REQUIRE_RATE_LIMITER` | O | `false` | N | image default | none | Leave `false`: a Valkey outage must not take `/health/ready` down. |

### 2.3 Customer authentication (OTP, sessions, cursors)

| Name | Req | Default | Secret | From | Who | Effect / UAT value |
|---|---|---|---|---|---|---|
| `TAZZZO_CONSUMER_CURSOR_HMAC_KEY_B64` | R | none | Y | SSM-SS | operator | base64 of >= 32 random bytes. Missing: product LIST routes answer 503. |
| `TAZZZO_CUSTOMER_AUTH_ACCESS_TOKEN_HMAC_KEY_B64` | R | none | Y | SSM-SS | operator | base64 of >= 32 bytes. Missing: every `/v1/customer/**` call is 401. |
| `TAZZZO_CUSTOMER_SESSION_REFRESH_HMAC_KEY_B64` | R | none | Y | SSM-SS | operator | base64 of >= 32 bytes. Missing: session establish/refresh answer 503. |
| `TAZZZO_CUSTOMER_AUTH_OTP_HMAC_KEY_B64` | R | none | Y | SSM-SS | operator | base64 of >= 32 bytes. Missing: every OTP call answers 503. **The four keys above must all be different** (`KeySeparationInvariant`, startup). |
| `TAZZZO_CUSTOMER_AUTH_PREVIOUS_ACCESS_TOKEN_HMAC_KEYS_B64` | O | empty | Y | SSM-SS | operator | Comma list, verification only, during an access-key rotation. |
| `TAZZZO_OTP_PROVIDER_MODE` | R | none (OTP request = 503) | N | SSM-S | operator | `LOGGING`, `HTTP` or unset. **Read section 5: `LOGGING` is refused under `staging` and never prints the code.** |
| `TAZZZO_OTP_REQUEST_IP_CAPACITY`, `TAZZZO_OTP_REQUEST_IP_REFILL`, `TAZZZO_OTP_REQUEST_PHONE_CAPACITY`, `TAZZZO_OTP_REQUEST_PHONE_REFILL`, `TAZZZO_OTP_VERIFY_IP_CAPACITY`, `TAZZZO_OTP_VERIFY_IP_REFILL`, `TAZZZO_OTP_VERIFY_CHALLENGE_CAPACITY`, `TAZZZO_OTP_VERIFY_CHALLENGE_REFILL` | R | `0` (503 at request time) | N | SSM-S | operator | All eight > 0. They use the same Valkey store as the consumer limiter. |
| `TAZZZO_CUSTOMERAUTH_OTP_HTTP_URL` | S (HTTP mode) | none | N | SSM-S | operator | `https` URL of the SMS gateway (plain http only for loopback). Startup failure in HTTP mode if empty. **Not listed by infra.** |
| `TAZZZO_CUSTOMERAUTH_OTP_HTTP_AUTHHEADERVALUE` | S (HTTP mode) | none | Y | SSM-SS | operator | Credential header value; never logged. Spelling follows relaxed binding (infra marks it UNVERIFIED). |
| `TAZZZO_CUSTOMERAUTH_OTP_HTTP_AUTHHEADERNAME`, `..._SENDER`, `..._MESSAGETEMPLATE`, `..._CONNECTTIMEOUTMILLIS`, `..._READTIMEOUTMILLIS` | O | `Authorization`, none, built-in text, 2000, 3000 | N | SSM-S | operator | `OTP_GATEWAY.md`; the template must contain `{otp}`. |
| `TAZZZO_OTP_TTL_SECONDS`, `TAZZZO_OTP_RESEND_COOLDOWN_SECONDS`, `TAZZZO_OTP_MAX_ATTEMPTS`, `TAZZZO_OTP_GRANT_TTL_SECONDS`, `TAZZZO_OTP_DELIVERY_TIMEOUT_SECONDS` | O | 300, 30, 5, 300, 60 | N | image default | none | Policy values; keep. |
| `TAZZZO_CUSTOMER_SESSION_ACCESS_TOKEN_TTL_SECONDS`, `TAZZZO_CUSTOMER_SESSION_TTL_SECONDS` | O | 900, 2592000 | N | image default | none | Access token 15 min, session 30 days. |

### 2.4 Staff and CMS authentication

| Name | Req | Default | Secret | From | Who | Effect / UAT value |
|---|---|---|---|---|---|---|
| `TAZZZO_ADMIN_OIDC_ISSUER` | S (CMS login) | empty | N | SSM-S | operator | `https://accounts.google.com`. **All four OIDC values or none** (any one set makes the others mandatory, startup failure). |
| `TAZZZO_ADMIN_OIDC_AUDIENCE` | S | empty | N | SSM-S | operator | Google OAuth client id of the CMS; equals the CMS `GOOGLE_CLIENT_ID`. |
| `TAZZZO_ADMIN_OIDC_HOSTED_DOMAIN` | S | empty | N | SSM-S | operator | Lower-case Workspace domain; equals the CMS `GOOGLE_HOSTED_DOMAIN`. |
| `TAZZZO_ADMIN_OIDC_CREDENTIAL_LABEL` | S | empty | N | SSM-S | operator | Audit label, for example `uat`. |
| `TAZZZO_ADMIN_USERS_0_PROVIDER` (`google`), `..._0_SUBJECT` (Google `sub`), `..._0_EMAIL`, `..._0_ROLES_0` (`reader`, `cms-writer`, `audit-reader`, `order-ops`, `support-agent`), `..._0_ENABLED` | S | no allowlist | N | SSM-S | operator | `tazzzo.admin.users[n].*` has no placeholder and **is not in the infra list**. Without an entry no Google identity gets a role: the runbook step "CMS login" cannot pass. One block per person (`_1_`, `_2_`...). Roles never come from token claims. |
| `TAZZZO_CMS_TOKEN`, `TAZZZO_READ_TOKEN` | O | empty (role disabled, fail closed) | Y | SSM-SS | operator | Service tokens for tooling. `deploy.sh` requires them to exist although the backend does not. |

### 2.5 Media, scheduler, commerce switches, HTTP

| Name | Req | Default | Secret | From | Who | Effect / UAT value |
|---|---|---|---|---|---|---|
| `TAZZZO_SCHEDULER_ENABLED` | R | `true` | N | SSM-S | operator | `true`: the single host is API and worker. The other worker switches (`TAZZZO_IMPORT_JOBS_ENABLED` true, `TAZZZO_NOTIFICATION_DISPATCH_ENABLED` false) keep their defaults. |
| `TAZZZO_MEDIA_STORAGE_PROVIDER` | S | `disabled` | N | SSM-S | operator | `s3` only after the media gates of the infra repo. With `disabled`, `staging` **refuses** new media references (`MediaIngestVerifier`). |
| `TAZZZO_MEDIA_S3_BUCKET`, `TAZZZO_MEDIA_S3_REGION` | S (with `s3`) | empty, `ap-south-1` | N | SSM-S | operator | Bucket output of Terraform. Credentials come from the instance role. |
| `TAZZZO_MEDIA_S3_ENDPOINT`, `TAZZZO_MEDIA_S3_PATH_STYLE`, `TAZZZO_MEDIA_S3_ACCESS_KEY`, `TAZZZO_MEDIA_S3_SECRET_KEY` | **must stay unset** | empty / false | access/secret: Y | none | none | For MinIO only. A static key in UAT would break the infra rule "no static AWS key". |
| `TAZZZO_MEDIA_PUBLIC_BASE_URL` | S | empty (no image URLs) | N | SSM-S | operator | `@Value("${tazzzo.media.public-base-url:}")`, no placeholder in `application.yml`. CDN origin, for example `https://media-uat.tazzzo.com`. |
| `TAZZZO_CHECKOUT_DELIVERY_SLOT_REQUIRED` | O | `false` | N | SSM-S | operator | `@Value`, not in `application.yml`. Keep `false`: UAT has no slot data and `true` refuses every order without a slot. |
| `TAZZZO_HTTP_CORS_ALLOWED_ORIGINS` | O | empty (CORS off) | N | SSM-S | operator | Exact https origins; normally unset (the CMS calls the backend from its server). |
| `TAZZZO_SHUTDOWN_GRACE` | O | `25s` | N | image default | none | Compose `stop_grace_period` is 40 s, so it fits. |
| `TAZZZO_HTTP_MAX_BODY_BYTES`, `TAZZZO_HTTP_BULK_IMPORT_MAX_BODY_BYTES`, `TAZZZO_HTTP_CONTENT_BLOCK_MAX_BODY_BYTES` | O | 65536, 2097152, 262144 | N | image default | none | nginx `client_max_body_size 3m` is above all three. |
| `TAZZZO_HEALTH_REQUIRE_MONGO` | O | `true` | N | image default | none | Readiness pings MongoDB. |

### 2.6 Keys read with `@Value` that have no entry in `application.yml` (all optional, defaults shown)

`tazzzo.delivery.horizon-days` 3, `tazzzo.delivery.zone` `Asia/Kolkata`, `tazzzo.freshness.enabled` false, `tazzzo.orders.customer-cancel-window-seconds` 0 (**customer cancellation is disabled by default**), `tazzzo.media.max-upload-bytes`, `tazzzo.media.max-pixels`, `tazzzo.media.max-dimension`, `tazzzo.customer-checkout.quote-ttl-seconds` 300 (30..3600), `tazzzo.customer-cart.max-distinct-items` 50, `tazzzo.customer-cart.max-quantity-per-item` 20, `tazzzo.customer-address.max-active-addresses` 10, `tazzzo.inventory-reservation.ttl-seconds` 600 (60..3600), `tazzzo.inventory-reservation.expiry-batch-size` 100, `tazzzo.membership.plans` (one plan in `application.yml`), `tazzzo.benefits.rules` (deliberately absent: no discount). None of them needs a UAT value.

## 3. Tunables with an `application.yml` placeholder and a safe default (leave unset)

| Group | Names (default) |
|---|---|
| Card projection | `TAZZZO_SCHEDULER_CARD_REBUILD_MS` (15000), `TAZZZO_SCHEDULER_CARD_REBUILD_BATCH_SIZE` (200), `TAZZZO_SCHEDULER_CARD_RECONCILE_MS` (300000), `TAZZZO_SCHEDULER_CARD_RECONCILE_LIMIT` (500), `TAZZZO_SCHEDULER_CARD_RECONCILE_MAX_LIMIT` (20000), `TAZZZO_SCHEDULER_CARD_RECONCILE_FULL_PASS_MS` (14400000) |
| Import jobs | `TAZZZO_IMPORT_JOBS_ENABLED` (true), `TAZZZO_IMPORT_JOBS_TICK_MS` (5000), `TAZZZO_IMPORT_JOBS_BATCH_SIZE` (500), `TAZZZO_IMPORT_JOBS_TICK_BUDGET_MS` (30000), `TAZZZO_IMPORT_JOBS_LEASE_MS` (300000), `TAZZZO_IMPORTS_MAX_ROWS_PER_JOB` (250000), `TAZZZO_IMPORTS_MAX_ACTIVE_JOBS` (10) |
| Notifications | `TAZZZO_NOTIFICATION_DISPATCH_ENABLED` (false), `TAZZZO_NOTIFICATIONS_PROVIDER` (`disabled`; `sandbox` is refused outside local/test/dev and no real vendor adapter exists), `TAZZZO_NOTIFICATIONS_DISPATCH_MS` (5000), `TAZZZO_NOTIFICATIONS_BATCH_SIZE` (50), `TAZZZO_NOTIFICATIONS_LEASE_SECONDS` (60), `TAZZZO_NOTIFICATIONS_MAX_AGE_SECONDS` (3600), `TAZZZO_NOTIFICATIONS_BASE_BACKOFF_SECONDS` (30), `TAZZZO_NOTIFICATIONS_MAX_ATTEMPTS` (5), `TAZZZO_NOTIFICATIONS_METRICS_REFRESH_SECONDS` (15) |
| Observability, media, batch | `TAZZZO_OBSERVABILITY_GAUGE_REFRESH_SECONDS` (15), `TAZZZO_MEDIA_S3_PRESIGN_TTL_SECONDS` (300), `TAZZZO_COMMERCE_PRODUCT_BATCH_MAX_IDS` (50; 1..50, and every rate bucket capacity must stay above it) |

## 4. Cross-check against the infra branch (the result)

Compared: every backend-bound name in `UAT_SECRETS_NAMES.md` section 1, `REQUIRED_PARAMS` and `DERIVED_NAMES` in `common.sh`, `compose.yml` and `uat.env.tftpl`, against `application.yml` and the Java sources of `main`.

### 4.1 Names the infra passes that the backend does NOT read

**None for the backend.** All 43 backend-bound names (the infra list without the two routed elsewhere) are honoured: 38 by an explicit `${...}` placeholder in `application.yml`, and 5 by Spring binding of a property that has no placeholder:

| Infra name | Honoured by |
|---|---|
| `TAZZZO_MEDIA_PUBLIC_BASE_URL` | `@Value tazzzo.media.public-base-url` (`CommerceReadConfig`) |
| `TAZZZO_CHECKOUT_DELIVERY_SLOT_REQUIRED` | `@Value tazzzo.checkout.delivery-slot-required` (`OrderController`) |
| `TAZZZO_CONSUMER_TRUSTEDCALLERS_0_NAME` / `_0_SECRET` | `TrustedCallerProperties` (`tazzzo.consumer.trusted-callers[0]`) |
| `TAZZZO_CUSTOMERAUTH_OTP_HTTP_AUTHHEADERVALUE` | `HttpOtpGatewayProperties.authHeaderValue`; the spelling (no dash, no underscore between AUTH and HEADER) is the correct canonical form |

Names that look unread but are correct: `VALKEY_PASSWORD` and `ORIGIN_VERIFY_SECRET` are routed to other containers on purpose. `TAZZZO_CALLER_NAME`, `TAZZZO_CALLER_SECRET`, `TAZZZO_API_BASE_URL`, `TAZZZO_SITE_URL`, `TAZZZO_MEDIA_BASE_URL`, `TAZZZO_BACKEND_URL` belong to the web apps (`/tazzzo/uat/web/*`), not to the backend. `TAZZZO_VPC` in the Terraform sources is a tag name.

### 4.2 Backend requirements the infra does NOT supply

| # | Severity | Finding | Source in the backend | Needs |
|---|---|---|---|---|
| 1 | **Blocker** | Infra sets `TAZZZO_MIGRATION_ENVIRONMENT=staging` **and** `TAZZZO_OTP_PROVIDER_MODE=LOGGING`. The backend **refuses to start**: `provider-mode=LOGGING is refused in environment 'staging'`. | `OtpAuthConfig.otpDeliveryProvider` | infra decision, section 5 |
| 2 | **Blocker for runbook M** | Even where `LOGGING` is allowed, `LoggingOtpDeliveryProvider` logs only a masked phone and the expiry, **never the code**, and the database stores only an HMAC. An operator cannot read the OTP from the app log; the E2E has no way to log in. | `LoggingOtpDeliveryProvider` | infra decision, section 5 |
| 3 | High | No admin allowlist (`tazzzo.admin.users[n].*`): after Google sign-in nobody holds a role, so CMS login (runbook M) cannot succeed. | `HumanAdminSettings` | add `TAZZZO_ADMIN_USERS_0_*` to the secrets-names doc and runbook |
| 4 | High (if HTTP mode is chosen) | `TAZZZO_CUSTOMERAUTH_OTP_HTTP_URL` is not listed (only the auth value is); HTTP mode refuses to start without it. | `HttpOtpGatewayProperties.validate` | add the name |
| 5 | Medium | `tazzzo.customer-rate-limit.*` is not listed: `/v1/customer/**` runs **without a rate limit** (startup WARN only). | `CustomerRateLimitConfig` | add four names, decide numbers |
| 6 | Medium | `deploy.sh` `REQUIRED_PARAMS` has 12 names. A deploy passes the pre-check but the container fails at startup or answers 503 when these are missing: `TAZZZO_RATE_LIMIT_IP_*`, `TAZZZO_RATE_LIMIT_INSTALL_*` (startup failure in REDIS mode), `TAZZZO_OTP_PROVIDER_MODE` and the eight OTP bucket values (OTP 503), the four `TAZZZO_ADMIN_OIDC_*` (if all four are missing, human login is silently disabled). The result is a slow roll-back instead of a clear "missing parameter" message. | `ConsumerRateLimitProperties.requireCompleteForRedis`, `OtpAuthProperties.Bucket` | extend `REQUIRED_PARAMS` |
| 7 | Low | `TAZZZO_CMS_TOKEN` and `TAZZZO_READ_TOKEN` are required by `deploy.sh` but optional in the backend (unset = role disabled). Stricter, not wrong; just make sure the runbook says why. | `application.yml` | none |
| 8 | Info | `TAZZZO_MIGRATION_MODE` is not passed; the default `VERIFY` is what UAT needs. The migration job variables (`CONFIRM_*`, `OPERATOR`, `EXIT_AFTER_RUN`) are operator-run one-shot settings, correctly absent from the host. | `MigrationProperties` | none |

No backend change was needed or made for 1 to 8: every one is a configuration or infra-repo decision, and the OTP delivery behaviour is catalogue-independent business logic that this change must not alter without approval.

## 5. OTP in UAT: what works and what does not

| Mode | Starts under `staging`? | Delivers a code? | Can an operator read the code? |
|---|---|---|---|
| unset | yes | no: `POST /v1/auth/otp/request` answers 503 | n/a |
| `LOGGING` | **no, startup failure** | no | **no**: the code is never logged (`LoggingOtpDeliveryProvider`) |
| `HTTP` | yes | yes, to the gateway at `TAZZZO_CUSTOMERAUTH_OTP_HTTP_URL` | only at the gateway/vendor |

Consequence: runbook M "Real UAT OTP" needs mode `HTTP`. Options, for the owner to decide:

1. A real SMS vendor (needs India DLT registration; the infra runbook already lists it as open).
2. A small gateway that the owner controls, reachable over `https`, that accepts the documented POST (`OTP_GATEWAY.md`) and lets the operator read the message for one named test number. This keeps `staging` and its safety checks.
3. Label the environment `dev` so that `LOGGING` starts. Not recommended: `dev` also permits the OIDC `jwks-uri` override, the notification `sandbox` provider and unverified media references, and the code would still not be logged, so it does not even solve the problem.
4. A new UAT-only provider that logs or exposes the code: a backend business-logic change that needs explicit approval and a security review.

`scripts/uat-e2e-cod.sh` supports options 1 and 2 today: it takes the code from `UAT_OTP` or prompts for it with hidden input, and never prints it.

## 6. Checks after the first UAT deploy (operator, read-only)

1. `/health/ready` is 200 `UP` (the container started, so sections 4.2 #1 and the rate-limit rules passed).
2. `scripts/uat-smoke-test.sh https://api-uat.tazzzo.com` passes.
3. In the container log (names only): no `customer_rate_limit_not_enforced` once the numbers are set; no `otp_rate_limiter_unavailable`.
4. `scripts/uat-e2e-cod.sh --dry-run`, then the real run with the test number.
