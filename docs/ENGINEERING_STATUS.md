# Tazzzo Backend — Engineering Status

Single source of truth for what is actually built and verified in `tazzzo-backend`.
Reflects **current reality only** — nothing is marked complete unless verified from existing code.

Last updated: 2026-09-28

---

## Current

- **catalog-service** — the only deployable service in this repository, operated as a
  **production modular monolith**: the commerce domains below are real, tested modules
  *inside* this service, with boundaries enforced by ArchUnit. Standalone-service extraction
  is a FUTURE decision and is **not required** for production operation.
  - Language/runtime: **Java 21** (Temurin 21)
  - Framework: **Spring Boot 3.3.5**
  - Datastore: **MongoDB** (primary; event-sourced write path)
  - Cache/limiter: **Redis** (consumer rate limiter only)
  - Test rig: JUnit 5 + Testcontainers (MongoDB 7 replica set + Redis)

## Merged on `main`

`main` = `f9c88997c9fe6337e3e79188b510845befad65b5` — regression floor **842 tests green**.
PR-01 through PR-08 are **MERGED**:

- **PR-01** — Frozen commerce architecture: 15 ADRs + ADR-003.1 (`docs/architecture/`),
  frozen `/v1` OpenAPI contract (`docs/api/v1/`), ArchUnit module-boundary rules, backend CI.
- **PR-02** — Money (int64 paise, INR) + DTO/contract primitives (`commerce.contract`),
  Indian PIN rule `^[1-9][0-9]{5}$`.
- **PR-03** — **Pricing** domain module (`price_current` + price events, CAS writes,
  immediate-only pricing — Option A).
- **PR-04** — **Inventory** domain module (`(sku_id, fulfillment_location_id)` unique key,
  derived available/stockState, atomic oversell-safe reserve path).
- **PR-05** — **Media** domain module (references only: assetKey never URL at rest;
  `MediaUrlResolver` at the edge; no upload/S3/CDN provisioning).
- **PR-06** — **Serviceability** domain module (pincode → service area + INTERNAL fulfillment
  routing; non-product audit rail via `domain_events`).
- **PR-07** — **ProductCardBaseProjection** (`product_card_base`): derived/disposable,
  strictly location-agnostic, unified fresh-observation rebuild loop.
  **NOT LIVE** for serving until an explicit freshness mechanism ships (frozen gate).
- **PR-08** — **Runtime product enrichment** (`commerce.read`): internal
  `RuntimeProductCard/Page/ServiceArea` composer — one serviceability resolution per request,
  one batched inventory read per page (≤ `MAX_PAGE_SIZE=50`), frozen buyable rule,
  cross-location isolation proven. Squash merge `f9c8899`.
- **PR-09** — **Runtime product detail composition** (`commerce.read`): internal PDP composer
  sharing the ONE `ConsumerProductResolver` with the legacy consumer PDP (no forked
  merge/eligibility semantics), catalog-version freshness gate, no truncation of authoritative
  data. Squash merge `cc23c88`.
- **PR-10A** — **Production projection freshness foundation** (`commerce.read` + neutral queue):
  async work-queue-driven `product_card_base` rebuild (generation-guarded `ProjectionRebuildQueue`;
  Pricing/Media/Catalog source hooks; unique-lease worker; drift reconciler; gated scheduler),
  Pricing batch read + ephemeral `CurrentPriceOverlay`, CAS-guarded source-version watermark.
  Squash merge `4560a55` — **931-test regression floor**.
- **PR-10B** — **Public commerce read API** (`commerce.api`): the public `/v1` surface —
  `CommerceReadController` (categories, children, category-products, product detail,
  serviceability) delegating to reused consumer taxonomy + new commerce.read services;
  `SurfaceClassifier` exposes `/v1` as PUBLIC_CONSUMER; `RuntimeToDtoMapper` (omit unsupported,
  fail-fast on required); list uses batch canonical price + `CurrentPriceOverlay`; PDP overlays
  current price + release-scoped reachability; serviceability threads `serviceAreaVersion` as
  `long` end-to-end (never fulfillmentLocationId); commerce-route signed cursor preserving the
  legacy `/catalog/v1` 9-field wire format byte-for-byte while the commerce route binds a
  non-reversible location fingerprint into a 10th field (a location change on continuation is
  `INVALID_CURSOR`); `CommerceExceptionHandler` → frozen `ErrorEnvelopeDto`, with Mongo outages and
  Pricing/Inventory/Media/Serviceability domain exceptions (via `commerce.read`'s
  `DomainReadGuard`) mapped to `SERVICE_UNAVAILABLE` and unexpected programming failures left at
  500; freshness-readiness gate on lists; no request-time writes; `requestId()` fails fast rather
  than ever returning the literal string `"null"`. Frozen `/v1` OpenAPI corrected (404 on
  categories, 400 on products, internal projection schema removed) —
  **OPENAPI-INTERNAL-PROJECTION-DEBT CLOSED**. No response cache / no AWS / no app integration
  (deferred to PR-10C). Squash merge `25bbe7f` — **1003-test regression floor**.
- **PR-10C** — **Commerce cache and observability hardening** (`commerce.api`/`commerce.read`):
  operational hardening only, no public business-semantics change. Cache-header correctness (a
  real bug fixed: categories/children no longer risk inheriting a public cache header on an error
  response — `Cache-Control` is set only after success, and every error response forces
  `no-store`); deterministic, structurally-unambiguous `TaxonomyETag` for categories/children with
  correct RFC 7232 weak `If-None-Match` comparison; bounded `ConsumerObservability` failure
  classification (`Outcome.INTERNAL_ERROR` distinct from `UNAVAILABLE`, closed `FailureClass`
  vocabulary); `FreshnessObservability` (`catalog.repo`) instrumenting the PR-10A rebuild
  queue/worker/reconciler with a typed `RebuildResult` enum (no arbitrary-`String` tag API);
  `CommerceReadReadiness` internal readiness seam (`baseReady`/`listReady` split, no new public
  endpoint). No response cache, no AWS infra, no app integration. Squash merge `4718d51` —
  **1051-test regression floor**.
- **PR-11A** — **Customer authentication security foundation** (`com.tazzzo.auth`): a FOURTH
  HTTP surface, `CUSTOMER_AUTHENTICATED`, for `/v1/customer` + `/v1/customer/**`. The blanket
  `/v1/**` public rule is GONE — `SurfaceClassifier` now uses an explicit, per-family `/v1`
  public allowlist (`/v1/categories/**`, `/v1/products/**`, `/v1/serviceability` exact,
  `/v1/auth/**`); any other `/v1` path, including the bare `/v1` root, is `UNKNOWN` and denied
  by default until deliberately ratified — a future route is never public by accident.
  `/v1/auth/**` stays reserved PUBLIC for the future OTP/login/refresh/logout endpoints
  (PR-11B/11C — NOT implemented here). `CustomerPrincipal` (customerId + sessionId, opaque
  `CUS_*`/`SES_*` value objects, no phone/installationId/IP/email as identity).
  `CustomerAccessTokenCodec`: HMAC-SHA256 signed, length-prefixed (never delimiter-joined)
  versioned token binding version/customerId/sessionId/issuedAt/expiresAt; fail-closed key
  config (`tazzzo.customer-auth.access-token-hmac-key-b64`, no default); injected `Clock`;
  constant-time signature comparison; expired/future-issued (beyond a 30s tolerance) tokens
  rejected; a pathological signed epoch value fails closed as `MALFORMED_CLAIMS` rather than an
  uncaught exception; `issue()` rejects a zero/negative TTL as an issuer-contract bug.
  `CustomerAuthFilter` (`HIGHEST_PRECEDENCE + 2`, deterministically after `RequestIdFilter` and
  `ApiAuthFilter`) owns the `CUSTOMER_AUTHENTICATED` surface exclusively — `ApiAuthFilter`
  explicitly skips it (no CMS/read service token can authorize a customer route, and a customer
  token can never authorize `/api/**`); every failure flattens to one flat `401 UNAUTHENTICATED`
  with a generic `WWW-Authenticate: Bearer` challenge and `Cache-Control: no-store`, never
  revealing the internal rejection reason. `X-Tazzzo-Installation-Id` and client IP remain what
  they always were — anti-abuse dimensions only, proven unable to authorize or bind identity.
  **No OTP, no phone provider, no refresh/session persistence/revocation, no Profile/Address/
  Cart, no real `/v1/auth/**` or `/v1/customer/**` production endpoints yet** — PR-11A proves
  cryptographic and time validity only; session-lifecycle revocation is a PR-11C concern. Squash
  merges `375d02e` (foundation) + `0ecae78` (deny-by-default hardening) → `4ce798f` —
  **1142-test regression floor**.

- **docs** — status-doc-only correction folding PR-11A into "Merged on main". Squash `806d106`.
- **PR-11B — OTP challenge lifecycle and provider abstraction** (`com.tazzzo.auth.otp`): OTP
  request/verify only. Does **NOT** create a customer, a session, or issue any access/refresh
  token — see PR-11C. `Phone`: strict India-only value object (E.164 `+91XXXXXXXXXX` plus two
  explicitly-normalized domestic shorthands; every other shape/country rejected). `OtpVerifierCodec`:
  a keyed HMAC-SHA256 verifier persisted INSTEAD OF the plaintext OTP (a plain hash of a 6-digit
  code is offline-brute-forceable in ~1M guesses; the keyed verifier is not), a SEPARATE secret
  from the customer access-token key (`tazzzo.customer-auth.otp.hmac-key-b64`, no default,
  fail-closed `NOT_READY`), plus a non-reversible keyed phone digest for rate-limit bucket keys
  (never the raw phone). `customer_otp_challenges`: typed state machine
  (`PENDING_DELIVERY → ACTIVE → VERIFIED/LOCKED/EXPIRED/SUPERSEDED`, `PENDING_DELIVERY →
  DELIVERY_FAILED`), every transition an atomic Mongo CAS/transaction (never read-then-write-back);
  two independent partial-unique indexes (`delivering`/`active`) so a resend never invalidates the
  previous working code until the replacement is CONFIRMED delivered; the OTP validity window
  starts at confirmed delivery, never challenge creation; a stale `PENDING_DELIVERY` (crashed
  process) is detected and retired via the authoritative `Clock`, never relying solely on the
  async Mongo TTL sweep. The ACTIVE→VERIFIED transition and the grant insert are ONE Mongo
  transaction (`Tx`) — a transient failure aborts the whole thing, leaving the challenge ACTIVE, so
  a client retry with the same OTP is the recovery path; a duplicate-key on the grant's
  `challengeId` unique index is disambiguated by a read-and-compare, never blindly accepted. Every
  OTP outcome — unknown/wrong/locked/superseded/already-verified challenge — collapses to the SAME
  generic `OTP_INVALID` (no enumeration). A successful verify produces a
  `customer_otp_verified_grants` one-time login grant (`GRANT_*`, opaque, no PII) — the client
  receives ONLY the grant id, never the phone; consumption is atomic and exactly-once, and is a
  narrow internal contract with **no public HTTP endpoint** — PR-11C is its only caller (now
  implemented). `OtpDeliveryProvider`: interface abstraction, no default production implementation
  (missing provider ⇒ 503, never a silent discard); a `LOGGING` dev-only provider exists behind
  explicit opt-in, never logging the plaintext OTP; production SMS/WhatsApp integration remains
  explicitly out of scope. Rate limiting reuses the SAME `RateLimitStore` the consumer surface uses.
  `POST /v1/auth/otp/request` and `POST /v1/auth/otp/verify` are on the PRE-EXISTING `/v1/auth/**`
  `PUBLIC_CONSUMER` allowlist entry. Squash merges `a2d2016`/`8b962e6`/`71012b4`/`02e0cf5` →
  `250477d` — **1235-test regression floor**.

- **PR-11C — Customer account and session lifecycle** (`com.tazzzo.auth.session`): completes
  customer authentication — OTP verified grant → customer resolution/creation → session creation
  → access/refresh token issuance → refresh rotation → logout/session revocation. Does **NOT**
  cover customer profile editing, addresses, cart, checkout, orders, wallet, notifications, social
  login, admin auth, payment, or app integration — those remain future phases.
  - **`customers`**: minimal identity — `_id` (opaque `CUS_*`, CSPRNG), `phoneNormalized`
    (canonical identity, unique-indexed), `status`, `createdAt`, `updatedAt`, `lastLoginAt`. NO
    profile fields. `CustomerRepository.resolveOrCreate` is a single atomic
    `findOneAndUpdate(upsert=true)` against the unique phone index — Mongo itself serializes a
    concurrent first-login race, so two simultaneous logins for the same never-seen phone always
    converge on exactly one customer.
  - **Session establishment is ONE Mongo transaction**: consume the OTP grant (`purpose=LOGIN`,
    not expired, not already consumed) → resolve-or-create the customer → create the
    `customer_sessions` record. Any step failing aborts the WHOLE transaction — a transient
    persistence failure leaves the grant durably UNCONSUMED, so the client's own retry of
    `POST /v1/auth/session` with the same grant is the recovery path, no manual repair. (The
    PR-11B lesson applied again: an invariant requiring rollback is always thrown from INSIDE the
    `Tx` callback, never checked only after `tx.run()` returns — proven by a dedicated
    transaction-rollback test.)
  - **`customer_sessions`**: `_id` (opaque `SES_*`), `customerId`, `createdAt`, `expiresAt`
    (independent of access-token TTL; default 30 days), `revokedAt`, `refreshTokenDigest`,
    `refreshGeneration`, `lastRotatedAt`. `expiresAt`/`revokedAt` are APPLICATION predicates
    checked on every authorization-relevant query — the Mongo TTL index is cleanup only.
  - **Access tokens** reuse PR-11A's `CustomerAccessTokenCodec` unchanged in wire format
    (default 15 minute TTL, configurable, validated shorter than the session TTL at startup).
    **Key rotation added**: an optional list of PREVIOUS verification-only keys
    (`previous-access-token-hmac-keys-b64`) — new tokens always sign with the current key; a
    token signed under a previous key still verifies until that key is removed from the list, no
    wire-format/version change.
  - **Refresh tokens** are `<sessionId>.<32-byte CSPRNG secret>` — the session id is not secret
    (already inside the signed access token), so it doubles as a lookup key with no separate
    digest index needed. Only a keyed HMAC-SHA256 digest of the secret is ever persisted, using a
    DEDICATED key (`tazzzo.customer-auth.session.refresh-token-hmac-key-b64`) — separate from both
    the access-token key and the OTP key, no reuse across trust domains. Rotation is an atomic CAS
    (`findOneAndUpdate` filtered on the CURRENTLY-stored digest matching the presented token): of
    two concurrent requests presenting the SAME refresh token, only the first can possibly commit,
    the second gets the same generic auth failure a stale/reused/revoked/expired token gets — no
    enumeration of why.
  - **Session-revocation authority**: PR-11A's `CustomerAuthFilter` proved cryptographic/time
    validity only. PR-11C adds `SessionAuthority` (defined in the `com.tazzzo.auth` foundation
    package, implemented in `com.tazzzo.auth.session` — dependency inversion, no reverse package
    coupling): after crypto/time verification succeeds, the filter now ALSO confirms the named
    session is neither revoked nor expired against real persistence (correctness first; no caching
    layer in this PR — a documented scope decision, not an oversight). A missing `SessionAuthority`
    bean fails closed exactly like a missing signing key, never a silent bypass.
  - **Logout requires authentication despite `/v1/auth/**` being PUBLIC_CONSUMER** —
    `CustomerAuthFilter` explicitly skips that surface, so `SessionController` performs its OWN
    inline bearer verification for `/v1/auth/logout` only (reusing `CustomerAccessTokenCodec`
    directly), producing the byte-identical flat 401 shape the filter itself would. Logout is
    idempotent and never reveals whether a session was already revoked; it does not delete the
    customer.
  - Session establishment/refresh responses deliberately OMIT `sessionId` and the phone number.
  - **No customer profile/address/cart/checkout/orders, no logout-all, no social login, no
    payment.** Squash merge `d136d53` — **1296-test regression floor**.

- **PR-12A — Customer profile read and update** (`com.tazzzo.customer.profile`): authenticated
  `GET`/`PATCH /v1/customer/profile` — displayName + email only. A NEW domain package, deliberately
  NOT under `com.tazzzo.auth`/`com.tazzzo.auth.session` (auth stays the identity/session
  foundation; it must not accumulate customer-facing business data). Does **NOT** cover address,
  cart, checkout, order, payment, wallet, coins, notifications, loyalty, social login, admin
  customer editing, customer deletion, phone-number change, email verification, or avatar upload —
  those remain future phases.
  - **`customer_profiles`**: one document per customer, keyed by `customerId` AS `_id` (no
    separate customerId index, no duplicate customer/profile mapping) — `displayName`, `email`,
    `createdAt`, `updatedAt`, `version`. Never `phoneNormalized`/session state/anything auth owns.
  - **Absent-profile semantics**: a customer with no profile document yet reads as a deterministic
    default projection (`displayName=null`, `email=null`, `version=0`) — GET never creates one.
  - **Optimistic concurrency via ETag/If-Match**: `GET` returns `ETag: "profile-<version>"`;
    `PATCH` requires `If-Match`, absent → `428`, stale → `412` (persisted state unchanged). One
    atomic `findOneAndUpdate` filtered on `{_id, version: expectedVersion}` — `expectedVersion==0`
    additionally sets `upsert=true`, so a legitimate first-time create and the version check are
    the SAME atomic operation. A losing concurrent create surfaces as a `MongoCommandException`
    (code 11000) from the `findAndModify` command — NOT the `MongoWriteException` a plain
    insert/update would raise — caught and normalized to the same `412` a stale version gets.
  - **True partial-update semantics**: a field OMITTED from the PATCH body is untouched; a field
    PRESENT with JSON `null` clears it; a field PRESENT with a value sets it — via a small explicit
    `PatchField<T>` (ABSENT/PRESENT(null)/PRESENT(value)), not a plain nullable Java field. An empty
    (or entirely-unrecognized) patch body is `400 INVALID_REQUEST` by design, never a silent no-op.
  - **displayName**: trimmed, empty-after-trim → `null`, max 80 Unicode code points, control
    characters rejected — legitimate international names (`José`, `李明`) are never over-sanitized.
  - **email**: OPTIONAL, UNVERIFIED profile data — never an authentication identity, never usable
    to log in, no verification email sent, no global uniqueness enforced. Trimmed, whole-address
    lower-cased, max 254 chars, a practical (not full RFC 5322) shape check.
  - **customerId always comes from the verified `CustomerPrincipal`** (`CustomerPrincipalResolver`)
    — never request body/path/query/header; there is no `GET /v1/customer/{customerId}/profile`.
  - **Bounded Micrometer metrics** (`customer_profile_read_success`,
    `customer_profile_read_failure{reason}`, `customer_profile_update_success`,
    `customer_profile_update_failure{reason}`, `customer_profile_precondition_failed`) — `reason`
    is always the closed `CustomerProfileFailure.Reason` enum; never customerId/email/displayName/
    phone/sessionId/requestId/IP/installationId as a tag.
  - A persistence-layer outage maps to `503 SERVICE_UNAVAILABLE` on both GET and PATCH, never a
    fake `401` and never a raw `500`, and never leaks the underlying exception class/message.
  - **PATCH's identity check + profile write are ONE Mongo transaction** (`Tx.call`, on the
    SAME `ClientSession`) via a new `CustomerIdentityAuthority` foundation interface
    (`com.tazzzo.auth`, implemented in `com.tazzzo.auth.session` against the real `customers`
    collection) — a missing/ghost customer identity throws INSIDE the transaction callback, so
    no `customer_profiles` document can ever be committed for it. `Tx.call` is retry-safe: it
    returns the value straight from the driver's own `withTransaction` retry loop, never a
    mutable holder mutated across a transient-transaction-error retry (the PR-11B/PR-11C
    lesson, applied again). GET stays read-only/non-transactional by design (it can never
    create persisted state, so a momentary race there is inherently transient, not a
    data-integrity concern).
  - **PII-safe failure logging**: `CustomerProfileService` logs only the failing exception's
    TYPE, never the exception object/message — proven by a Logback `ListAppender` test that
    injects fake PII into a simulated exception message and asserts none of it reaches the
    emitted log output.
  - **Future invariant — customer deletion is NOT implemented by this PR.** PR-12A guarantees
    a profile PATCH may commit only if the corresponding customer identity exists in the SAME
    Mongo transaction snapshot that PATCH uses. When customer deletion/account closure is
    implemented in a future PR, that lifecycle MUST coordinate deletion/revocation of every
    dependent piece of state, at minimum: customer profile, addresses, sessions, and any future
    customer-owned state (carts, etc). Not solved here — deliberately out of scope for PR-12A.
  - Squash merges `943b6b1`/`c44bca8`/`4f4e42f` → `8d3b8fd` — **1363-test regression floor**.

`main` = `8d3b8fd50831933ab2e3bad1404d2bd72de2be7e` (PR-11A+status-doc + PR-11B squash `250477d` +
PR-11C squash `d136d53` + PR-12A squash `8d3b8fd`) — **1363-test regression floor**.

## In review (NOT merged)

- **PR-12B — Customer addresses and serviceability binding** (`com.tazzzo.customer.address`):
  authenticated CRUD of a customer's own saved delivery addresses
  (`GET/POST /v1/customer/addresses`, `GET/PATCH/DELETE /v1/customer/addresses/{addressId}`,
  `PUT /v1/customer/addresses/{addressId}/default`), plus dynamic serviceability binding. A NEW
  domain package, deliberately separate from `com.tazzzo.customer.profile` (sibling domains, both
  built on the `auth` foundation) and never touched by `com.tazzzo.serviceability` (one-way
  dependency only). Does **NOT** cover cart, checkout, order, payment, delivery slots, saved
  payment methods, customer deletion, address sharing, admin address editing, geocoding provider
  integration, or app integration — those remain future phases.
  - **`customer_addresses`**: one document per saved address, `_id` the opaque `ADDR_*` id. Every
    lookup/update/delete filters on BOTH `_id` AND `customerId` — an address is unreachable by
    guessing/enumerating an id (IDOR closed by construction). Deliberately does NOT store
    `isDefault` or any serviceability truth (`serviceable`, `serviceAreaId`,
    `fulfillmentLocationId`) as persisted state.
  - **`customer_address_state`**: ONE document per customer — `addressCount` (the address-limit
    CAS) and `defaultAddressId` (the single-default pointer). BOTH concurrency invariants this
    domain needs (a bounded per-customer address limit, and "at most one default address") are
    enforced entirely through atomic writes to this ONE document, replacing the naive
    per-address-field design: two address documents are two SEPARATE Mongo documents, so
    concurrent writes to two DIFFERENT address documents do not necessarily conflict (a write-skew
    hazard under snapshot isolation) — a single pointer field on one shared document makes "at
    most one default" a STRUCTURAL invariant, and any two concurrent racing writers for the same
    customer necessarily contend on the SAME document, where real MongoDB write-conflict detection
    guarantees exactly one wins. PR-12B REUSES the `Tx.call(Function<ClientSession, T>)` primitive
    introduced during PR-12A's final hardening pass (already on `main` before this PR branched —
    PR-12B does not modify `Tx.java`) — it returns T straight from the driver's own
    `withTransaction` retry loop, never a mutable holder. `isDefault` in every response is computed
    at read time (`addressId.equals(state.defaultAddressId)`), never stored redundantly.
  - **Identity integrity** (the PR-12A pattern, reused unchanged, including the existing
    `CustomerIdentityAuthority`/`CustomerRepository` session-scoped read seam — PR-12B does not
    modify `CustomerRepository.java` either): every mutation verifies the authenticated customer
    identity exists via `CustomerIdentityAuthority`, folded into the SAME transaction as the
    address mutation — a missing identity throws inside the callback, aborting before any write;
    GET/LIST verify non-transactionally (they can never create persisted state).
  - **Retry-safe by construction**: `AddressId` is generated ONCE, before entering any transaction,
    and reused across a driver-initiated retry — a retried attempt inserts the SAME logical
    address, never a duplicate. No mutable holder is used outside any callback anywhere in this
    domain (every transactional method returns straight from `Tx.call`/`Tx.run`).
  - **Serviceability binding reuses the EXISTING domain unchanged** — `ServiceabilityService
    .resolvePublic`, the SAME read the public `/v1/serviceability` commerce endpoint already uses.
    No parallel engine invented. The serviceability domain resolves by PIN ONLY today (no lat/lng
    input path exists), so evaluation always uses `postalCode`, truthfully reflecting the current
    contract even though valid lat/lng is persisted for future use. Evaluated FRESH on every
    read/response, never persisted as address state, so a serviceability configuration change is
    reflected on the very next read without rewriting the address document. Three-state result
    (`serviceable: true/false/null`) — `null` (UNKNOWN) is distinct from `false`
    (definite outside-coverage): a dependency failure never lies and reports unserviceable.
    `fulfillmentLocationId` is internal and never appears in any response. `serviceAreaId`/
    `serviceAreaVersion` are public in the existing `/v1/serviceability` contract; the address
    projection intentionally omits them (it is a narrower `serviceable`-only view).
  - **Saving an address never requires current serviceability** — address validity is independent
    of commerce eligibility; Checkout (future) re-evaluates serviceability again at purchase time.
  - **Optimistic concurrency via ETag/If-Match** (`"address-<version>"`), the SAME pattern as
    customer-profile: PATCH/DELETE require If-Match (missing → 428, stale → 412, persisted state
    unchanged). An unknown address id and another customer's address return the IDENTICAL 404 — no
    ownership enumeration.
  - **ETag semantics**: `"address-<version>"` is the address-CONTENT concurrency token used by
    PATCH/DELETE `If-Match` — it is NOT a complete version of the customer's default-preference
    state. `isDefault` lives in `customer_address_state`, so changing the default can change an
    address's representation while its `version` stays the same. Responses are `Cache-Control:
    no-store` and the version exists specifically for PATCH/DELETE optimistic concurrency, so no
    composite ETag was introduced (no concrete need).
  - **Failure-metric ownership**: `customer_address_failure{operation,reason}` is recorded in ONE
    place — `AddressExceptionHandler` — which sees every `AddressFailure` (controller-side
    request-shape failures and service-side domain failures alike) and malformed-JSON bodies
    exactly once, inferring the bounded `operation` from (method, path). The service records only
    successes, and only after `Tx.call`/`Tx.run` returns.
  - `setDefault` returns the address read INSIDE its successful transaction attempt — never a
    post-commit re-fetch that could race a concurrent delete.
  - **recipientPhone** is delivery-contact information only — never the customer's authentication
    identity, never used to log in, never used to issue an OTP, may differ from the login phone,
    never logged or placed in a metric tag.
  - Bounded Micrometer metrics (`customer_address_{list,read,create,update,delete,default_set}
    _success`, `customer_address_failure{operation,reason}`,
    `address_serviceability_result{result}`) — `operation`/`reason`/`result` are always closed
    enums; never customerId/addressId/phone/PIN/lat-lng/label text/requestId as a tag.
  - New ArchUnit rules: `auth`/`customer.profile`/`serviceability` must not depend on
    `customer.address`.
  - On `feature/pr12b-customer-address`.

## Blocked

- (none tracked)

## Tracked debt

- See [`docs/architecture/DEBT-REGISTER.md`](architecture/DEBT-REGISTER.md). No open entries —
  `OPENAPI-INTERNAL-PROJECTION-DEBT` closed in PR-10B.
- **Deferred (PR-10C review):** `CommerceListService` still performs its own direct `products`
  membership Mongo read rather than sharing a Catalog read seam with `ConsumerProductListService`;
  extracting it would require touching `ConsumerListGuardIT`'s structural pin on the legacy
  surface, judged out of scope for both PR-10B and PR-10C. The read is guarded (`MongoException`
  → 503), not left unguarded. A future PR may extract the shared seam alongside updating that
  guard test.

## Milestones

- **Public Commerce Read (PR-10A/B/C): COMPLETE.** The public `/v1` read surface (categories,
  children, category-products, product detail, serviceability) is live on `main`, operationally
  hardened (cache/ETag/observability/readiness), with a 1051-test regression floor.
- **Customer Auth (PR-11A/B/C): COMPLETE.** Authentication/session infrastructure only — OTP
  request/verify, grant consumption, customer resolution/creation, session creation, access/
  refresh tokens, refresh rotation, logout/session revocation. **This is NOT the customer profile
  domain.**
  - **PR-11A — customer auth security boundary: MERGED.** Fourth HTTP surface + principal/
    token cryptographic verification foundation, deny-by-default `/v1` classification.
  - **PR-11B — OTP challenge lifecycle + provider abstraction: MERGED.** OTP request/verify,
    produces an internal one-time login grant.
  - **PR-11C — customer account and session lifecycle: MERGED.** Grant consumption, customer
    resolution/creation, session creation, access/refresh tokens, refresh rotation, logout/session
    revocation.
- **Customer Profile (PR-12A): COMPLETE.** Authenticated `GET`/`PATCH /v1/customer/profile`
  (displayName + email only), a NEW `com.tazzzo.customer.profile` domain package — see "Merged on
  main" above. Customer deletion is explicitly NOT implemented (see the "Future invariant" note
  above — a future deletion/account-closure PR must coordinate profile/address/session/cart
  cleanup).
- **Customer Address (PR-12B): IN REVIEW.** Authenticated CRUD of saved delivery addresses plus
  dynamic serviceability binding, a NEW `com.tazzzo.customer.address` domain package — see "In
  review" above.
- **Serviceability**: the existing PR-06/PR-10B foundation (`com.tazzzo.serviceability`,
  `PublicServiceability`, pincode-keyed routing) is **COMPLETE** and unchanged; the
  customer-address BINDING to it (PR-12B) is **IN REVIEW**.
- Cart/Checkout/Order/Payment: **NOT STARTED**.

## Next (ratified sequence)

1. **PR-12C+** — Cart, Checkout, Orders, Search, Notifications, app integration, AWS
   infrastructure: not started, not scoped yet.

## Not started (honest boundary)

**Domain modules implemented inside catalog-service** (Pricing, Inventory, Media,
Serviceability, projection/read composition) **are built and tested** — see above. What does
NOT exist is any standalone extracted service. **No code exists** for:

- auth-service
- customer-service
- inventory-service *(extraction only — the Inventory domain module itself is implemented
  inside catalog-service, PR-04)*
- cart-service
- checkout-service
- order-service
- search-service
- notification-service

Do not invent implementation status for Auth/Cart/Order/Search/etc. Microservice extraction
is FUTURE work and not required for the production modular monolith.

> Note: catalogue CMS / attribute-governance / offers logic also lives **inside**
> `catalog-service`; any future extraction (e.g. a separate cms-service) is a design
> decision, not yet started.

## History

- Repository restructuring: `tazzzo-backend` created; `catalog-service` imported under
  `services/catalog-service` (history-preserving subtree, 39 commits, root `9807372` → `5e4ef5a`);
  migrated tree confirmed byte-identical to the original catalog repo (565/565 at migration).

## Last verification

- **2026-09-28** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 + Docker
  (MongoDB 7, Redis via Testcontainers), on `feature/pr11c-customer-session-lifecycle` at head,
  based on `main` squash `250477d` (post-PR-11B baseline, 1235 tests): **BUILD SUCCESS**,
  **1268 tests, 0 failures / 0 errors / 0 skipped**.
- **2026-09-28** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 +
  Docker (MongoDB 7, Redis via Testcontainers), on `main` at squash merge `4ce798f`
  (post-PR-11A baseline): **BUILD SUCCESS**, **1142 tests, 0 failures / 0 errors / 0 skipped**.
  `backend-ci` green on the same commit (Compile & test, Validate API contracts).
- **2026-09-27** — `./mvnw clean test` in `services/catalog-service` on Java 21.0.12 +
  Docker (MongoDB 7, Redis via Testcontainers), on `main` at squash merge `4718d51`
  (post-PR-10C baseline): **BUILD SUCCESS**, **1051 tests, 0 failures / 0 errors / 0 skipped**,
  ~1:45 min.
