# Tazzzo Backend — Engineering Status

Single source of truth for what is actually built and verified in `tazzzo-backend`.
Reflects **current reality only** — nothing is marked complete unless verified from existing code.

Last updated: 2026-10-04

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
  - Merged as PR #19: pre-merge head `c443e22`, squash `64042f6` — **1478-test regression floor**;
    merged-main backend-ci run `36396355618` (Compile & test + Validate API contracts: SUCCESS).

`main` = `64042f6bc79168a284f84f7fa337a455a7def7a3` (PR-11A+status-doc + PR-11B squash `250477d` +
PR-11C squash `d136d53` + PR-12A squash `8d3b8fd` + PR-12B squash `64042f6`) — **1478-test regression floor**.

- **PR-12C — Customer cart foundation**: merged as PR #21 — pre-merge head `a85584d`, squash
  `ce868f4212531b6461678989869a76beaea7f004`; merged-main backend-ci run `36453966063`
  (Compile & test + Validate API contracts: SUCCESS) — **1544-test regression floor**.
  - Scope (`com.tazzzo.customer.cart`): **COMPLETE (merged)**. Authenticated
  `GET /v1/customer/cart`, `PUT`/`DELETE /v1/customer/cart/items/{skuId}`, `DELETE /v1/customer/cart`.
  Cart is purchase INTENT only (SKU, quantity, timestamps, version) in `customer_carts` keyed by
  customerId; current price/stock/serviceability/buyable are composed at read time through the
  existing commerce enricher (new `CommerceSkuBatchReader` seam in `commerce.read`), never persisted.
  ETag `"cart-<version>"` + mandatory If-Match (logical version 0 = no cart); expiry 7 days after the
  last mutation evaluated at runtime with NO Mongo TTL so the version never resets; every mutation is
  identity check + write in one `Tx.call`. Not a reservation, not a final total, no checkout.
  - **Review hardening (included in PR #21):** (M1) a non-JSON `Content-Type` on the cart write routes is a
    safe `415 UNSUPPORTED_MEDIA_TYPE` (not a 500); every unexpected 500 increments
    `customer_cart_failure{reason="internal"}` exactly once (`CartObservability.internalFailure`, kept
    OUT of `CartFailure.Reason` — a defect is not a domain outcome). (M3) the visible-facts → base →
    fail-closed base → current-price overlay composition now lives in ONE package-private seam,
    `commerce.read.CurrentCardBaseComposer`, used by both `CommerceListService` and
    `CommerceSkuBatchReader`; `ProductCardRuntimeEnricher` remains the single stock/serviceability/
    buyable composer. The public list's projection-gap fallback now takes `productId`/`catalogVersion`
    from fresh catalog facts (previously `productId=sku`, `catalogVersion=0`) — same as the cart.
  - **Known, accepted limitations (documented, not blocking):** eligibility is read per SKU (≤50 point
    reads per cart response; batch `findEligibleCards` is follow-up debt — M2). `GET` may perform a
    version-guarded housekeeping write when it finds an expired cart (ETag can advance with time). SKU
    visibility is checked before the mutation transaction (a SKU hidden in that window can still be
    added; Checkout must revalidate). A quantity change on a line whose SKU has become hidden is 404
    (removal still works). The expiry counter is named `cart_expired` (not `customer_cart_*`). An
    arithmetic overflow in response totals after commit is unreachable under the item/price/quantity
    bounds. Non-11000 duplicate-key wrapper variants map to 503 rather than 412. `Accept`-header (406)
    mismatches are not specially mapped.

- **PR-11D — Auth transaction retry safety**: merged as PR #22 — pre-merge head `2b57e3a`, squash
  `5c7e4df72bda7e06e543d34e67b7f414e2abff5a`; merged-main backend-ci run `36458268694` (Compile & test +
  Validate API contracts: SUCCESS) — **1557-test regression floor**.
  - Scope: `OtpService.verify` (confirmed defect:
  a result stored in an external holder by a transaction attempt whose commit was rolled back could
  survive into a retry that lost the CAS and be returned as success), `OtpService.request`, and
  `CustomerSessionService` establish/refresh now return their result from `Tx.call` (an immutable
  value from the committed attempt) instead of a mutable holder written inside `tx.run`. `Tx` documents
  the multiple-invocation contract. Deterministic retry tests (`RetryInjectingTx`: real Mongo
  transaction, labeled transient error after the body, the driver's own retry loop) plus a structural
  guard against Auth result holders. No public contract change.

`main` = `8b4fabb9b82e4e3502202ffaff1aadd35f9ba311` — **1625-test regression floor**.

- **PR-13A — Checkout validation and quote foundation** (`com.tazzzo.customer.checkout`): **COMPLETE**.
  Merged as PR #23 — pre-merge head `4d8c479`, squash `1f73668785372b957b60dfc2bbb40bf4bf944188`;
  merged-main backend-ci run `36501097297` (Compile & test + Validate API contracts: SUCCESS) —
  **1617-test regression floor**.
  Hardened after final review: (M1) an idempotent POST replay of an EXPIRED quote returns 410
  `QUOTE_EXPIRED` (never 200, never re-priced, never a replacement quote) in every replay path
  (pre-validation, in-transaction, duplicate-key-race-winner) — a new quote after expiry needs a new
  Idempotency-Key. (M2) the address is re-verified INSIDE the persisting transaction by BOTH id and
  `version` (not existence alone), so a deletion or edit of the address between validation and commit
  is rejected (404) rather than silently persisting a quote against stale/gone address state; only
  ownership/version is rechecked, never serviceability (that stays outside the transaction). (M3)
  `CheckoutQuote`'s compact constructor now validates every invariant a future Order would trust
  (quoteId/addressId shape, line arithmetic, no duplicate SKU, itemCount/subtotal cross-checked with
  `Math.*Exact`, `createdAt < expiresAt`, currency == INR) — a corrupt persisted document fails loud as
  an uncaught `IllegalArgumentException`/`ArithmeticException`, which the public boundary maps to a
  safe 500 `INTERNAL` (never a leaked value, never disguised as a 503 dependency outage).
  `POST /v1/customer/checkout/quote` (If-Match cart ETag + Idempotency-Key + `{addressId}`) and
  `GET /v1/customer/checkout/quotes/{quoteId}`. Revalidates the whole cart against CURRENT commerce
  truth through the SAME seam the cart uses (`CartEnricher` → `CommerceSkuBatchReader` → the unchanged
  `ProductCardRuntimeEnricher`; no second price/stock/serviceability/buyable algorithm), for one OWNED
  address; all-or-nothing; persists an immutable short-lived (5 min, configurable, injected `Clock`)
  quote in `checkout_quotes` (int64 paise, `CHKQ_` opaque id, unique `(customerId, idempotencyKeyDigest)`
  index, NO TTL index so expired stays distinguishable: GET → 410). One `Tx.call`: identity still exists →
  idempotency re-check → cart RECHECK (version unchanged and not expired) → insert. Idempotent replay
  returns the original quote (not re-priced, expiry not extended). **A quote is NOT a reservation**: no
  stock is held and price is not locked beyond the snapshot; a future Order MUST revalidate stock and
  quote validity. No routing identity is stored or exposed. No Order/Payment/COD/slots/coupons/GST.

- **PR-13B — Checkout quote address provenance** (`com.tazzzo.customer.checkout`): **COMPLETE**.
  Merged as PR #24 — pre-merge head `bc5b611`, squash `8b4fabb9b82e4e3502202ffaff1aadd35f9ba311`;
  merged-main backend-ci run `36504827179` (Compile & test + Validate API contracts: SUCCESS) —
  **1625-test regression floor**.
  `CheckoutQuote` now carries `addressVersion` — the EXACT version of `addressId` that commerce
  validation ran against (captured once from `CheckoutService.ValidatedAddress`, never re-read,
  never client-supplied), validated `>= 0` by the compact constructor like every other invariant.
  Persisted in `checkout_quotes`; a row written before this field existed (or corrupted to drop it)
  fails loud (no default to 0) — the public boundary maps that to a safe 500 `INTERNAL`, never
  200/404/410/503. **INTERNAL-ONLY provenance**: not in `CheckoutQuoteDto`, not in the public
  OpenAPI response, no OpenAPI diff at all. Exists so a future Order can prove the saved address
  has not changed since the quote was validated (`currentAddress.version == quote.addressVersion`),
  without a second address read. No Order, Inventory Reservation, or Payment code in this PR.

## Merged on `main` (continued)

- **PR-14A — Inventory reservation lifecycle** (`com.tazzzo.inventory`): **COMPLETE** (PR #25,
  squash `44438031022238334ecdf3995ba1096101e2e47f`). Hardened
  after final review: (M1) `prepare(InventoryReservationRequest)` is the ONLY way to obtain a
  reservation command — reservation id and TTL are Inventory's own policy, never caller-supplied;
  the PUBLIC opaque `PreparedInventoryReservation` type has a package-private constructor (a caller
  in another package can hold and pass one by name — no `var` trick needed — but cannot write
  `new PreparedInventoryReservation(...)` — compiler-enforced, not just documented). (M2) `InventoryReservationObservability` is now actually wired: standalone wrappers
  record success/failure/transition ONLY after their own `Tx.call` commits; session-aware port
  calls never touch it. (M3) any `MongoException` that escapes a standalone wrapper's own
  transaction is mapped to a typed `UNAVAILABLE`, never a leaked Mongo type; session-aware methods
  still let transient/write-conflict errors propagate untouched so `Tx.call`'s own retry keeps
  working. (M4) `InventoryReservationExpiryWorker` counts `released`/`inventory_reservation_expired`
  ONLY when its own call actually caused the `RESERVED -> RELEASED` transition (via an internal
  `InventoryReservationLifecycleResult`), never when it merely observed a race it lost against an
  explicit release or a confirming consume.

  **Final contract hardening (this review):** (H1) expiry is RUNTIME-AUTHORITATIVE, never
  merely "whatever the reconciliation worker hasn't gotten to yet" — `reserve` re-checks
  `expiresAt` against Inventory's LIVE injected `Clock` (never `preparedAt`) both for a fresh,
  already-stale prepared command and for an idempotent replay whose durable header is itself
  `RESERVED`-but-expired, and `consume` refuses an expired hold outright — both throw the new
  closed `RESERVATION_EXPIRED` reason before touching any inventory row or reservation status;
  `release` is deliberately NOT expiry-gated, since releasing an expired hold IS the recovery
  path. (M1, this review) the duplicate-key winner re-read is isolated in its own try/catch
  (`resolveDuplicateWinner`) so a datastore failure DURING that recovery read can no longer
  escape as a raw Mongo type — a real Java gotcha: an exception thrown inside one `catch` block
  is never caught by a sibling `catch`. (M2, this review) the prepared-command type became a
  PUBLIC class (`PreparedInventoryReservation`, package-private constructor) instead of a
  package-private record relying on `var` — a normal, nameable cross-package contract, proven
  by a genuine cross-package test (`com.tazzzo.external`). (L1) value-object invariants keep
  throwing `IllegalArgumentException` (existing repo convention); `prepare()` maps only a null
  request to `INVALID_REQUEST` — a caller cannot reach `prepare()` with a malformed request at
  all, since `InventoryReservationRequest` itself refuses to construct one.

  The real
  order-facing reservation lifecycle `InventoryService.tryReserve`'s own javadoc said did not yet
  exist (no id/expiry/release/reconciliation/idempotency). `InventoryReservationService` +
  `InventoryReservationPort` (`reserve`/`release`/`consume`, session-aware — participates in a
  CALLER's transaction, never starts its own) over `inventory_reservations` (opaque `RESV_` id,
  unique `orderId` index = one-reservation-per-order + concurrent-create guard, NO TTL so an
  expired/released header stays inspectable). Multi-SKU reserve is ONE Mongo transaction: a
  per-SKU conditional update (`InventoryService.reserveOneSkuInSession`, shared with the legacy
  single-SKU `tryReserve` so there is exactly ONE reservation-mechanics implementation) in a
  deterministic sorted order, any failing line aborts the whole transaction and MongoDB itself
  rolls back every earlier line's increment — no manual undo anywhere. Idempotent by `orderId` +
  a semantic fingerprint (same input replays the durable reservation; different input is a typed
  `ALREADY_RESERVED_DIFFERENT_INPUT` conflict). `release`/`consume` are CAS status-guarded
  (`RESERVED` only), idempotent when already terminal, and reject the wrong terminal transition.
  Expiry is reconciled by `InventoryReservationExpiryWorker` calling the SAME `release` lifecycle
  (never a bulk bypass), scheduled behind the two-flag `tazzzo.scheduler.enabled` +
  `tazzzo.scheduler.inventory-reservation-expiry-enabled` gate (off in every test profile — same
  discipline as `CommerceProjectionScheduler`). Session-aware success is never counted as durable
  (only the standalone wrappers record success, after their own commit) — a future Order composing
  the port owns its own outer-transaction success metric. New ArchUnit rule:
  `inventory` may never depend on any `customer.*`/`order`/`payment` package. No Order, Payment, or
  gateway code in this PR.

  **PR-14B preparation-contract evolution** (see the PR-14B entry below): `prepare` now takes only
  an `orderId` (no location/items) and `reserve` takes a separate caller-constructed
  `InventoryReservationAllocation` (location + items) — the earlier `InventoryReservationRequest`
  type this block describes has been removed; the standalone `reserve(orderId, location, items)`
  convenience method's external signature/behavior is unchanged.

- **PR-14B — Order Foundation** (`com.tazzzo.customer.order`): **COMPLETE** (PR #26, squash
  `b620538e34afc35f7f080461750681b467ed4096`). *(PR-15A-1 below evolves this entry's schema and adds COD
  placement.)* Internal Order
  creation from an owned, unexpired `CheckoutQuote`, atomically alongside an Inventory reservation.
  The durable `(customerId, quoteId)` Order row is the idempotency authority: once it exists, it
  wins over every later mutable authority (quote expiry, address, serviceability, price, catalog
  eligibility, stock) by construction — the ONLY reads before the existing-Order check (both the
  pre-transaction fast path and the in-transaction authoritative one) are the Order lookup itself
  and `CheckoutQuoteRepository.findOwnedQuote` (new — loads the immutable quote WITHOUT judging its
  expiry, unlike the customer-facing `CheckoutService.readQuote`). Every mutable read (address,
  serviceability, pricing, catalog eligibility, inventory) happens exclusively INSIDE one outer
  `Tx.call`, after that check. New session-aware companion ports — `TransactionalPriceReadPort`,
  `TransactionalServiceabilityReadPort`, `TransactionalCatalogCardReadPort` — added as SEPARATE
  interfaces (not new abstract methods on the existing `PriceReadPort`/`ServiceabilityReadPort`/
  `CatalogCardReadPort`, which stay functional-interface-compatible for existing lambdas/stubs);
  `PricingService`/`ServiceabilityService`/`CatalogCardReader` implement both, sharing one
  decode/algorithm each. Order (`OrderId` opaque `ORD_` id, `OrderStatus.CREATED` only this PR,
  `OrderLine`/`OrderAddressSnapshot` immutable snapshots, fail-loud `Order` compact constructor, no
  discount/tax/fee/payment field) persists to `orders` with a unique `(customerId, quoteId)` index
  (structural one-quote-one-order + concurrent-create guard), no status/history index, no TTL.
  Failure enum: `INVALID_REQUEST, QUOTE_NOT_FOUND, QUOTE_EXPIRED, ADDRESS_CHANGED, NOT_SERVICEABLE,
  PRICE_CHANGED, PRODUCT_UNAVAILABLE, STOCK_UNAVAILABLE, RESERVATION_EXPIRED, INTEGRITY_FAILURE,
  UNAVAILABLE` — no `SERVICEABILITY_CHANGED` (one authoritative route, read once, inside the
  transaction) and no `ALREADY_EXISTS_DIFFERENT_INPUT` (unreachable: a quote is immutable, so
  replaying order-creation for it always means the same order). New ArchUnit rules: upstream
  modules never depend on `customer.order`; Order never depends on the concrete
  `PricingService`/`ServiceabilityService`/`CatalogCardReader`/`InventoryService`/
  `InventoryReservationRepository` or the general-purpose non-session ports, only the transactional
  companions and `InventoryReservationPort`; Order never depends on future Payment/Admin. No
  Payment, COD confirmation, prepaid flow, gateway, Membership, Benefits/Promotion, coupons,
  Admin/CMS, fulfilment workflow, or customer-facing Order HTTP endpoint in this PR.

- **PR-15A-0 — Cart purchase-finalization seam** (`com.tazzzo.customer.cart`): **COMPLETE** (PR #28, squash
  `611829c3649de3f5c37dec4ac5b375a1d8ef454e`). First
  of three PR-15A steps (15A-1 Order COD domain, 15A-2 customer HTTP). Cart-only: no Order code, no
  HTTP. Adds `CartPurchasePort` (session-aware, joins the CALLER's transaction, never opens one,
  records no metrics) so a future COD placement can keep a purchased cart from yielding a second
  Order. Multiple checkout quotes can exist for one cart version (quote uniqueness is per
  idempotency key), so the cart owns a monotonic `purchasedThroughVersion` (`$max`; absent = 0;
  never lowered/reset; document never deleted; never exposed in any DTO).
  `isSourceVersionPurchased` = `marker >= sourceCartVersion` (read-only; the caller's own durable
  same-quote replay check runs FIRST and never reaches it). `finalizePurchase`: live == source ->
  clear items, advance version, raise marker, one guarded update (`CLEARED`); live > source ->
  items/version/timestamps untouched, marker raised only (`NEWER_CART_PRESERVED`, a normal result,
  never a failure); no cart document / live < source / corrupt marker ->
  `CartPurchaseIntegrityException`, aborting the caller's transaction. `ModuleBoundaryTest` gains a
  rule that `customer.order` may reach the cart only through this port and its result/integrity
  types. **Operational gate (PENDING, not verified from the repo):** the `orders` collection must
  contain 0 documents in every deployed persistent environment before PR-15A-1 is deployed.

- **PR-15A-1 — COD Order domain** (`com.tazzzo.customer.order`): **COMPLETE** (PR #29, squash
  `e8d4d45e88ad4935f7ad84a46ae65a671e50ba63`). Domain only — customer reachability comes only with
  PR-15A-2 below.
  `OrderService.placeCodOrder(customerId, quoteId)` atomically produces, in ONE outer Mongo
  transaction: Order `CONFIRMED` (version 2, `paymentMethod=COD`, `confirmedPaymentCondition=COD_DUE`,
  `confirmedAt`), its Inventory reservation `CONSUMED` (reserve then consume in the same session via
  `InventoryReservationPort`), `purchasedThroughVersion` raised, and the source cart cleared only if
  still at the quote's version (`CartPurchasePort`, PR-15A-0). A COD placement never commits an
  intermediate `CREATED`, so there is no recoverable two-transaction gap and no dead-`CREATED` failure
  mode; a COD reservation is never committed `RESERVED`, so the expiry worker has nothing to race.
  Sequence inside `Tx.call`: durable same-quote replay (authoritative, FIRST — `CONFIRMED` returned
  untouched, never rejected by the cart marker) -> quote expiry + identity -> **cart-purchase guard**
  (`CART_VERSION_ALREADY_PURCHASED`, before any destructive write) -> address / serviceability /
  price / catalog / allocation / reserve -> consume (+ order-linkage check) -> cart finalization ->
  insert. The shared checks live in ONE package-private `OrderDraftAssembler` (session-aware, opens no
  transaction, records no metric) used by both the internal create-only path (`CREATED`, version 1,
  `RESERVED` reservation — package-private `createOrder`, no customer-reachable caller, kept for a
  future prepaid composition) and COD placement, which differ only in how they finish. Strict Order
  schema: `version`, `paymentMethod`, `confirmedPaymentCondition`, `confirmedAt` are required where the
  status demands them and a persisted row missing one **fails loudly — no compatibility shim, no
  defaults**. `COD_DUE` means confirmed + inventory consumed + payment owed on delivery; it does not
  mean collected/authorized/captured, and a future `COD_COLLECTED` is a separate event. Only `COD` and
  `COD_DUE` exist (no prepaid/`PAYMENT_SUCCEEDED`/gateway vocabulary). New failure reason
  `CART_VERSION_ALREADY_PURCHASED`; Inventory `RELEASED`/expired at consume maps to
  `RESERVATION_EXPIRED`; cart integrity problems map to `INTEGRITY_FAILURE`; a duplicate key (11000) is
  recovered by re-reading the durable same-(customer, quote) Order outside the aborted transaction —
  found: strictly reconstructed and replayed (a COD `CREATED` winner still fails closed); not found:
  `INTEGRITY_FAILURE`. Nothing depends on MongoDB's error text or index name. Metrics
  `order_place_cod_success`/`order_place_cod_failure{reason}` recorded only after the outer operation
  returns. No Membership, Benefits, prepaid, gateway, COD collection, cancellation, fulfilment or Admin.
  **DEPLOYMENT / PRODUCTION ROLLOUT BLOCKED UNTIL the deployed persistent `orders` collection has
  document count == 0 in EVERY persistent environment receiving this strict schema — PENDING, NOT
  verified (repository inspection is not proof).** If rows are ever found, stop: an explicit
  migration-or-deletion decision is required before deploying. Do not claim production readiness while
  this is pending.

- **PR-15A-2 — Customer Order HTTP surface** (`com.tazzzo.customer.order`): **COMPLETE** (PR #30, squash
  `0cdcc97b8fcf5f7c079815b8cb276874635d15d6`). The FIRST
  customer-reachable Order surface: `POST /v1/customer/orders` and `GET /v1/customer/orders/{orderId}`.
  **COD placement became customer reachable in code when this PR merged; it is NOT deployed or
  production-ready while the `orders`-count gate below is PENDING.** Both endpoints are
  `CUSTOMER_AUTHENTICATED`; the customer is always the verified principal (never read from body, path,
  query or headers). POST reads only `quoteId` + `paymentMethod` (exactly `COD`; anything else is 400
  `PAYMENT_METHOD_UNSUPPORTED`, missing/non-string is 400 `INVALID_REQUEST`, a malformed quote id is the
  domain's 404; unknown body fields are ignored, per the existing customer-controller convention) and
  delegates ONLY to `OrderService.placeCodOrder`; it never reaches the internal create-only path
  (ArchUnit-enforced) and has no `Idempotency-Key` (`(customerId, quoteId)` is already unique). Always
  `200`, first placement and replay alike. GET is one owned query (`_id` AND `customerId`, never
  load-then-authorize) via `OrderService.getOrder`; a malformed, unknown, foreign or internal-`CREATED`
  id is the identical 404; a corrupt stored row is a safe 500; an outage is 503. The response
  (`CustomerOrderDto`) is built only from the Order's stored snapshots and hides customerId, quoteId,
  reservationId, addressId/version, Order version, fulfillment location and coordinates; `paymentMethod`
  `COD` + `paymentCondition` `COD_DUE` mean confirmed, payment owed on delivery, nothing collected. All
  responses `no-store`; errors are `{code, message, requestId}`. Failure mapping: 400/404/410, 409
  (`ADDRESS_CHANGED`, `NOT_SERVICEABLE`, `PRICE_CHANGED`, `PRODUCT_UNAVAILABLE`, `STOCK_UNAVAILABLE`,
  `RESERVATION_EXPIRED`, `CART_VERSION_ALREADY_PURCHASED`), 500 `INTERNAL`, 503. No double counting:
  the domain owns `order_place_cod_*`; the HTTP layer counts only request-shape rejections, unexpected
  500s and every GET outcome. OpenAPI gains both paths and their schemas. No order list endpoint, no
  Membership, Benefits, prepaid, gateway, COD collection, cancellation or fulfilment.
  **Optional coordinates (found by real-HTTP testing, fixed in this PR):** the address API treats
  coordinates as OPTIONAL (both absent, or both present and valid), but the merged Order snapshot required
  them, so a coordinate-less saved address crashed placement (safe, rolled-back 500). `OrderAddressSnapshot`
  now follows the Address contract: `Double latitude/longitude`, both null, or both finite and in range
  (lat [-90,90], lon [-180,180]); exactly one present, out-of-range or non-finite fails loud; nothing is
  ever defaulted (`0.0`, `NaN`, a city centre) or geocoded. `snapshotFrom` and `OrderRepository` preserve
  null safely. Coordinates remain internal and are never in `CustomerOrderDto`; address creation/patch,
  checkout and PIN-based serviceability are unchanged.
  **DEPLOYMENT /
  PRODUCTION ROLLOUT remains BLOCKED on the `orders`-count == 0 gate (PENDING, not verified).**

- **PR-16A-1 — Membership write foundation** (`com.tazzzo.membership`, a NEW top-level domain peer of
  `inventory`/`pricing`, its own ArchUnit slice): **COMPLETE** (PR #31, squash
  `d32a23fb2e4b52fa8076de45a07bf3b60912b1e2`). **Foundation only — at merge there was no
  entitlement read seam (PR-16A-2 follows), no REVOKED/cancellation (PR-16A-3), no renewal, no worker, no
  HTTP endpoint (no purchase, no customer status), no Benefits (no discount percentages, thresholds or
  coupons anywhere), no Payment/prepaid/gateway and no customer subscription purchase.** The only
  operation is an INTERNAL, idempotent, payment-free `MembershipService.grant(customerId, planId,
  planVersion, internalReference)` for a trusted orchestrator that has already established the customer
  (Membership checks the `CustomerId` shape only and never queries customer existence); nothing outside
  the package may depend on it (ArchUnit). A term carries no payment facts: its plan price is the list
  price at grant, not money collected.
  - **Model.** `memberships`, one document per TERM, `_id` `MBR_*`; `MembershipStatus` is exactly
    `ACTIVE` and `EXPIRED` (each has a producer: grant, and lazy expiry during a later grant). Plan facts
    (`planId`, `planVersion`, `planPricePaise`, `planCurrency`, `planPeriodMonths`) are SNAPSHOTTED so a
    later plan edit cannot reinterpret history; `billingZoneId` (`Asia/Kolkata`) is persisted on every term
    and strictly required (no "missing means Kolkata" shim). Strict reconstruction validates every field
    and that `validUntil` equals the one billing-calendar formula.
  - **Billing calendar.** `MembershipBillingCalendar`: monthly periods are calendar months in
    `Asia/Kolkata` (anchored on the original activation, Java month-end clamping, exact
    `Math.multiplyExact`, fail-loud on overflow), independent of the JVM default zone; timestamps stay
    `Instant`/Mongo dates truncated to milliseconds. Only that class may do calendar arithmetic and no
    Membership class may read ambient time (both ArchUnit-enforced; time is the injected `Clock`).
  - **Launch plan.** `TAZZZO_PLUS_MONTHLY` v1, 9900 paise INR, 1 month, from an immutable config-backed
    `MembershipPlanSource` validated at startup (empty list, duplicates, bad id/version/price/currency/
    period, bad or overlapping version windows and an open-ended non-final version all fail the start).
    Plan effectiveness gates NEW grants only; a durable idempotent replay wins before it.
  - **One open term per customer** is a storage guarantee: `openTerm` is the BSON boolean `true` only
    while `ACTIVE` and is `$unset` (never false/null) when terminal; partial unique index
    `membership_one_open_per_customer`. `membership_one_per_grant_reference` is unique on
    `(grantSource, grantRef)` (`GrantSource` is exactly `INTERNAL_GRANT`; the caller never supplies it).
    No TTL, history or expiry-scan index. **Membership has exactly THREE indexes:** `membership_one_open_per_customer` (partial
    unique), `membership_one_per_grant_reference` (unique) and `membership_active_by_customer` `{customerId, status}`
    (NON-unique; added with the entitlement read).
  - **Concurrency.** One transaction repeats the reference check, applies plan effectiveness at a fresh
    per-attempt clock read, and either rejects (`now < validUntil` => `ALREADY_ACTIVE`) or CAS-expires the
    time-ended term (`_id`, status, version AND `validUntil <= now`; unsets `openTerm`) and inserts the
    replacement — together or not at all. A duplicate key (code 11000, never message/index name) is
    resolved from durable PRIMARY reads: reference row => replay / `GRANT_REF_CONFLICT`; open term still
    holding the slot => `ALREADY_ACTIVE`; otherwise ONE bounded whole-grant retry, then
    `INTEGRITY_FAILURE`. Non-transactional reads use a handle pinned to `ReadPreference.primary()`
    (global Mongo settings untouched).
  - **Failure/metrics.** `MembershipFailure`: `INVALID_REQUEST`, `PLAN_NOT_ACTIVE`, `ALREADY_ACTIVE`,
    `GRANT_REF_CONFLICT`, `INTEGRITY_FAILURE`, `UNAVAILABLE`. Metrics (closed enum tags only) are recorded
    after the whole operation resolves, never per transaction attempt.
  - **Membership operational gates — all PENDING, NOT verified from the repository, to be confirmed
    before deployment:** (1) no pre-existing conflicting `memberships` collection/schema in each persistent
    environment; (2) `SchemaBootstrap` has collection/index privileges; (3) the effective
    `tazzzo.membership.plans` configuration (and, since the Benefits static config hardening, the effective
    `tazzzo.benefits.rules` configuration; and, since human admin OIDC, the effective `tazzzo.admin.oidc.*` and
    `tazzzo.admin.users` configuration) is identical across environments and application instances as intended; (4) the deployed
    Mongo URI has no unexpected `readPreference` override; (5) the deployed cluster default read/write
    concern is as assumed. These do not block source review.
  - **The existing Order deployment gate is unchanged and still PENDING:** the `orders` collection must
    have document count == 0 in every persistent environment. Nothing here weakens or replaces it; do not
    claim production readiness.

- **PR-16A-2 — Membership entitlement read seam** (`com.tazzzo.membership`): **COMPLETE** (PR #32, squash
  `580633abbc8d162f45110443f503d4998f4caa48`). **READ ONLY.**
  Answers one question: does this customer have a Membership entitlement at Membership's own current
  authoritative time? Two narrow ports and one minimal value type: `MembershipEntitlementPort`
  (standalone, `currentEntitlement(CustomerId)`), `TransactionalMembershipEntitlementPort` (session-aware,
  `currentEntitlement(ClientSession, CustomerId)`) and `MembershipEntitlement(membershipId, planId,
  planVersion, validUntil)` — no customerId, status, version, grant reference, price, period, zone or
  timestamps. Neither port accepts a caller-supplied time: the injected `Clock` is read fresh per call.
  - **Runtime truth:** `status == ACTIVE AND validFrom <= now < validUntil` (half-open, millisecond exact).
    A persisted ACTIVE row whose window has ended (valid stale state) or that has not started (clock skew)
    yields `Optional.empty()` and is NOT mutated (no EXPIRED persisted, no version bump, no `openTerm`
    unset, no transaction, no grant call); lazy persistence stays owned by the PR-16A-1 grant path. An
    EXPIRED term is empty. `Optional.empty()` means authoritatively no entitlement — an outage
    (`UNAVAILABLE`) or a corrupt row (`INTEGRITY_FAILURE`, via strict reconstruction) is never collapsed to
    it. **Candidate query (hardening):** the entitlement read fetches the customer's current/open
    CANDIDATES — `customerId AND (status = ACTIVE OR openTerm exists)` — served by the NON-unique index
    `membership_active_by_customer` `{customerId, status}` (the plan is a bounded scan of that one customer's
    key range plus a fetch filter and `LIMIT 2`; no collection scan, no new index for the `openTerm` branch).
    It is NOT the write-slot query and NOT all history: valid terminal rows (EXPIRED, marker absent) are
    ignored. Every candidate goes through strict reconstruction, so any row that is or CLAIMS the current
    membership fails loud — an ACTIVE row with a missing/false/null/non-boolean marker, or an unknown or
    terminal status carrying `openTerm` (`INTEGRITY_FAILURE`) — and `empty` keeps meaning "authoritatively no
    entitlement". More than one candidate is itself an `INTEGRITY_FAILURE` (never pick one; this also guards a
    missing/dropped unique index). The partial unique `membership_one_open_per_customer` remains the sole
    write/concurrency uniqueness authority (used by the grant path).
  - **Transactional contract:** joins the caller's session, opens no transaction, mutates nothing, emits
    ZERO metrics (the caller's `Tx.call` may retry), never falls back to the standalone read. A transient
    transaction error propagates untouched so the caller's retry works; any other datastore failure is a
    typed `UNAVAILABLE`. The implementation has no `Tx`, observability or Micrometer dependency
    (ArchUnit-enforced). The standalone read uses the primary-pinned handle and records only
    `membership_failure{operation=entitlement_read,reason}`; there is no entitlement-result metric.
  - **Snapshot:** an entitlement reports the stored TERM facts; the read implementations have no plan
    source and never reinterpret a term against the current plan configuration (ArchUnit + test).
  - **Boundary:** ArchUnit now also fixes the future direction Benefits -> Membership through an
    allowlist (the two ports, `MembershipEntitlement`, `MembershipId`, `MembershipFailure`/`Reason`
    only). No Benefits, Checkout, Order, Cart, Pricing, Inventory, Serviceability or Commerce change;
    no HTTP; no Payment; no grant/renewal/cancel/revoke/worker change.
  - **Deployment gates unchanged and PENDING:** the Order `orders`-count == 0 gate and the five Membership
    gates recorded under PR-16A-1 (no conflicting `memberships` collection, SchemaBootstrap privileges,
    identical plan configuration, no Mongo `readPreference` override, cluster default read/write concern).
    Not verified; do not claim production readiness.

- **PR-16A-3 — Membership termination** (`com.tazzzo.membership`): **COMPLETE** (PR #33, squash
  `99e2d1b7cc26982e0825bc4a9766b815aa6ae8e0`). The last Membership
  Foundation slice: exactly two INTERNAL, standalone lifecycle commands in `MembershipTerminationService` —
  **no HTTP, no Benefits, no Payment/refund, no renewal, no upgrade, no audit subsystem (no actor/reason),
  no worker, no new index, no session-aware termination seam** (no caller needs one).
  - **New producer/state:** `MembershipStatus.REVOKED` (terminal, never an entitlement, `openTerm` ABSENT) and two
    additive nullable timestamps `cancelRequestedAt` / `revokedAt` (absent on every pre-existing row, never
    backfilled). Cancel-at-period-end is NOT a status: it is the `cancelRequestedAt` fact on an ACTIVE term.
    Strict reconstruction rejects every impossible combination (`INTEGRITY_FAILURE`): REVOKED with an open marker
    (true/false/null) or without `revokedAt`; `revokedAt` on a non-REVOKED row; non-date/null representations;
    timestamps outside `[validFrom, validUntil)`; a REVOKED/cancelled row not last updated by its own mutation. A
    valid historical EXPIRED row may carry `cancelRequestedAt` but never an open marker. PR-16A-2's candidate read is
    unchanged (REVOKED rows carry no marker so they are not candidates; REVOKED + marker is a claimed-open corrupt row
    and fails loud).
  - **`cancelAtPeriodEnd(CustomerId)`:** resolves the customer's single current/open term (the established candidate
    read, no history scan). On an entitling term with no prior request it sets `cancelRequestedAt = now` (service
    clock, ms precision), version + 1; status, `openTerm`, `validUntil` and every snapshot field are untouched and
    entitlement continues until the window ends. Idempotent: an already-cancelled term is returned with NO mutation
    (original timestamp, version and `updatedAt` stable; a recorded request replays as success even after the window
    ended — the replay check intentionally precedes the window check so an already-succeeded command stays safely
    replayable across `validUntil`). No current/open term => `NOT_FOUND`: this includes a customer whose term was just
    revoked (REVOKED rows carry no open marker, so cancel-after-revoke and a revoke-wins cancel/revoke race are
    `NOT_FOUND`, never `INVALID_TRANSITION`; the command targets current lifecycle authority, so there is deliberately
    NO historical REVOKED lookup). A FIRST cancel request on a stale (window ended) or not-yet-started ACTIVE term
    (no prior `cancelRequestedAt`) => `INVALID_TRANSITION` with no mutation (never rewritten as REVOKED or silently
    expired); corrupt candidate => `INTEGRITY_FAILURE`. There is no un-cancel.
  - **`revoke(MembershipId)`:** on an entitling ACTIVE term it sets `status = REVOKED`, `revokedAt = now`, version + 1
    and REMOVES `openTerm` (`$unset`, never false); a prior `cancelRequestedAt` is preserved as history; the slot is
    freed for a new grant and entitlement ends immediately. Idempotent on an already REVOKED term (first `revokedAt`
    stable). Unknown id => `NOT_FOUND`; EXPIRED, stale or not-yet-started ACTIVE => `INVALID_TRANSITION`; corrupt row
    => `INTEGRITY_FAILURE`. `NOT_FOUND` and `INVALID_TRANSITION` join the closed failure vocabulary (real producers);
    there is no `VERSION_CONFLICT`.
  - **CAS / transactions:** each command owns exactly one `Tx.call`; an authoritative same-session read precedes a CAS
    guarded on `_id`, `status = ACTIVE`, expected `version`, `openTerm = true`, the live window and (cancel) the
    absence of a previous request. A concurrent writer surfaces as a transient write conflict that the driver resolves
    by re-running the callback, which re-reads and lands on the stable domain result; a CAS miss after a same-session
    read is `INTEGRITY_FAILURE`. Metrics (`cancel_at_period_end`, `revoke` operations; the `ACTIVE -> REVOKED`
    transition) are recorded once, only after `Tx.call` returns; closed-enum tags only.
  - **Tests and boundaries:** unit reconstruction tests; `MembershipTerminationIT` (domain, idempotency, entitlement,
    retry, outage, repository CAS guards); real-Mongo concurrency (cancel/cancel, revoke/revoke, cancel/revoke,
    revoke/grant) asserting the FINAL persisted document under a ticking clock. Four new ArchUnit rules (nothing
    outside Membership depends on the termination service; it depends on no other domain, HTTP or plan source;
    repository/domain objects emit no metrics; entitlement reads never call the termination writes). New rules and
    five production mutations (cancel rewrite, missing `$unset`, stale revoke, CAS bypass, REVOKED without
    `revokedAt`) were mutation-checked.
  - **Roll-forward constraint:** once persisted `REVOKED` (or `cancelRequestedAt`) rows exist, code that cannot
    reconstruct them (PR-16A-1/16A-2 builds) must NOT be redeployed — it fails loud on a REVOKED row.
  - **Deployment gates unchanged and PENDING (none verified):** the Order `orders`-count == 0 gate and the five
    Membership gates (no conflicting `memberships` collection, SchemaBootstrap privileges, identical plan
    configuration, no Mongo `readPreference` override, cluster default read/write concern). Do not claim
    production readiness.

- **Benefits Foundation — membership-backed benefit evaluation seam** (`com.tazzzo.benefits`, a NEW top-level
  domain, the first and only authorized Membership consumer): **COMPLETE** (PR #34, squash
  `cb2097f5979fe666d0d52de958b40db7058e3d16`). **First Benefits foundation only:
  internal, read-only, no persistence, no HTTP, no Checkout/Order change, no coupons, Payment untouched.**
  - **Benefit shape (exactly one):** an ORDER-LEVEL percentage discount with an INCLUSIVE minimum eligible
    subtotal. Not implemented: flat discount, free delivery, buy-X-get-Y, SKU/category discount, cashback, coins,
    member-only price, coupon stacking, usage limits, priority. Coupon precedence stays unresolved.
  - **Value types:** `DiscountBps` (`0..10000` basis points, `10000 = 100%`, integer only; no floating point, no
    `BigDecimal`) and the existing `Money` (INR paise, non-negative) — no second money type. **Rounding:**
    `discountPaise = floor(subtotalPaise * bps / 10000)`, computed overflow-free as `q*bps + (r*bps)/10000`
    (`q = paise/10000`, `r = paise%10000`), tested against exact `BigInteger` division and at `Long.MAX_VALUE`.
    A floor discount of 0 paise is NOT an applied benefit.
  - **Rule model/source:** `BenefitRule(planId, planVersion, minimumSubtotal, discountBps)` keyed by the EXACT
    `(planId, planVersion)` the `MembershipEntitlement` reports (the persisted term snapshot, never the live plan
    config). Zero bps, a zero minimum, a bad plan id/version and non-INR are rejected, and so is a rule that is not
    economically real at its own threshold (`floor(minimum * bps / 10000) >= 1` paise: 9,999 paise at 1 bps is
    rejected, 10,000 paise at 1 bps is accepted), so `subtotal >= minimum` always means a positive discount. The
    minimum stays strictly positive (a business wanting "every non-empty basket" configures 1 paise);
    `DiscountBps(0)` itself remains a valid value type. `ConfigBackedBenefitRuleSource` is immutable, validates at
    construction (duplicate keys and every malformed rule fail application start, never an evaluation) and may be
    empty. **No launch percentage or threshold is ratified: no production Benefits rule is configured — the
    `tazzzo.benefits.rules` property is absent (absent and an explicitly empty list both bind to an empty rule
    set)**, so an entitled customer resolves to `NO_RULE`. The client prototype's 5% / 500 / 10% / spend-milestone are NOT
    backend policy and appear only as test fixtures. There is NO latest-version, plan-id-only or default fallback.
  - **Ports:** `BenefitsEvaluationPort.evaluate(CustomerId, Money eligibleSubtotal)` (standalone; owns no
    transaction, writes nothing) and `TransactionalBenefitsEvaluationPort.evaluate(ClientSession, CustomerId, Money)`
    (joins the caller's session via `TransactionalMembershipEntitlementPort`; opens no transaction, writes
    nothing, emits ZERO metrics, never falls back to the standalone path, and a transient driver error propagates
    untouched for the caller's own retry). Both share one pure decision (`BenefitEvaluator`).
  - **Result:** `BenefitEvaluation` = `NoBenefit(NO_MEMBERSHIP | NO_RULE | NOT_ELIGIBLE)` (normal, never an error) or
    `Applied(membershipId, planId, planVersion, eligibleSubtotal, discountAmount, discountBps)` — enough for a
    later Checkout snapshot to persist its authority without re-running rules.
  - **Failures (closed, Benefits-owned, only reasons with a runtime producer):** `INVALID_REQUEST`, `UNAVAILABLE`,
    `INTEGRITY_FAILURE`. `MembershipFailure` never leaks: `INVALID_REQUEST`/`UNAVAILABLE`/`INTEGRITY_FAILURE` map
    one-to-one and any other Membership reason is `INTEGRITY_FAILURE`; an outage or corruption is never reported as
    `NoBenefit`. The rule source is an immutable in-process configuration whose defects fail at construction, so
    there is no runtime rule-source failure; `BenefitRuleSource` stays the domain seam, and a future persistent source
    introduces its own failure reason in its own PR.
  - **Membership boundary:** Benefits never inspects status, `openTerm`, `cancelRequestedAt`, `revokedAt` or
    `validFrom`; Membership owns lifecycle (cancel-at-period-end keeps the benefit until `validUntil`; revoke
    removes it at once). Benefits depends on Membership ONLY through `MembershipEntitlementPort`,
    `TransactionalMembershipEntitlementPort`, `MembershipEntitlement`, `MembershipId`, `MembershipFailure`/`Reason`
    (ArchUnit-enforced).
  - **Observability:** standalone only, `benefits_failure{operation=evaluate,reason}` (closed enums; no customer,
    membership, plan, version, subtotal, discount or exception text). A normal `NoBenefit` records nothing.
  - **Architecture:** nine new ArchUnit rules (`ModuleBoundaryTest` 47 -> 56): no Membership implementation
    dependency, no commerce/Payment/Admin/HTTP/`Tx` dependency, no HTTP surface, metrics only in the standalone
    service, the transactional evaluator has no metrics/transaction/standalone fallback, Benefits only READS
    Membership, only Benefits consumes the entitlement seam (Checkout/Order/Cart cannot bypass it), and nothing
    upstream depends on Benefits.
  - **Not in this PR:** persistence (no Benefits collection, no index, no schema change), HTTP, Checkout/Order/Cart/
    Pricing change (discounted money still needs the separate snapshot fields of the later money-model upgrade),
    coupons, Payment, renewal.
  - **Deployment gates unchanged and PENDING (none verified):** the Order `orders`-count == 0 gate and the five
    Membership gates. Do not claim production readiness. Roll-forward constraint (PR-16A-3) still applies:
    once persisted `REVOKED` rows exist, code that cannot reconstruct them must not be redeployed.

- **Order Benefits snapshot — authoritative Benefits evaluation at COD placement** (`com.tazzzo.customer.order`,
  first Checkout + Order money-model slice): **COMPLETE** (PR #35, squash
  `4486044e1c2f200ed05fe44abbb6525bf24a98f7`). Persistence/domain authority only: **no Checkout
  Benefits preview, no public HTTP/OpenAPI change, no Payment, no payable/final-total field, no coupons, no tax or
  fees, no production Benefits rule (0 configured).**
  - **Evaluation point:** inside the existing Order placement `Tx.call` (COD placement and the internal create-only
    path alike), in `OrderDraftAssembler`, after the durable-replay check and the exact canonical price revalidation and
    BEFORE the Inventory reserve, through `TransactionalBenefitsEvaluationPort` with the Order's own `ClientSession`
    (no standalone port, no nested transaction, no Benefits retry layer, zero Benefits metrics; a transient driver
    error propagates untouched to the Order's own outer retry). A Benefits failure aborts the whole transaction.
  - **eligibleSubtotal = the Order's canonical merchandise subtotal** (the sum of its line totals). No tax, delivery,
    platform, packaging or service fee, coupon, coin or wallet exists in the backend, so none participates. **Aggregate
    discount only**: no line allocation, no net unit price, no discounted line total. Canonical Pricing money
    (`OrderLine.unitPricePaise`/`lineTotalPaise`, `Order.subtotalPaise`) is never rewritten, and no top-level
    discounted/payable total is persisted (the Payment-facing amount is not defined yet).
  - **`OrderBenefitSnapshot`** (immutable, nested `benefits` document, conditional presence, no placeholders):
    `NO_BENEFIT {eligibleSubtotalPaise, noBenefitReason: NO_MEMBERSHIP | NO_RULE | NOT_ELIGIBLE}` or
    `APPLIED {eligibleSubtotalPaise, discountPaise, discountBps, membershipId, planId, planVersion}`, copied
    exactly from `BenefitEvaluation` (Order does no Benefits arithmetic and never touches Membership; `Applied` gained
    two flat read-only accessors, `membershipIdValue()` and `discountBpsValue()`, for that). Invariants: the
    snapshot's eligible subtotal equals the Order subtotal; `APPLIED` has `1 <= discountPaise <= subtotal`,
    `discountBps` in 1..10000, non-blank `membershipId`/`planId`, `planVersion >= 1`.
  - **Absent snapshot = legacy Order** (created before this slice), never "no benefit"; no backfill, no migration, no
    new collection, no index. **Every Order created by the new code path stores one**, including the normal
    outcomes: today's production result is `NO_BENEFIT/NO_RULE` for a member and `NO_BENEFIT/NO_MEMBERSHIP` for a
    non-member. A normal no-benefit outcome is a successful placement (no rollback, no failure metric).
    Reconstruction is strict (unknown outcome/reason, missing or foreign fields, wrong BSON types, an explicit null,
    any violated invariant all fail loud, the existing Order convention) and never drops a malformed snapshot.
  - **Failure mapping (Order vocabulary unchanged):** Benefits `UNAVAILABLE` -> Order `UNAVAILABLE`; Benefits
    `INTEGRITY_FAILURE`, `INVALID_REQUEST` (Order built the call) and any unexpected reason -> Order
    `INTEGRITY_FAILURE`; a Benefits result inconsistent with the Order subtotal -> `INTEGRITY_FAILURE`.
  - **Idempotency:** `(customerId, quoteId)` unchanged; a durable replay returns the stored Order untouched and NEVER
    re-runs Benefits, so a later Membership change or Benefits configuration change cannot alter it.
  - **Accepted snapshot-isolation semantics:** the authoritative result is the Mongo transaction snapshot the attempt
    observed. A Membership revoke committed before that read is seen; one committed after may still commit with the
    entitlement the attempt observed. The existing Pricing race is likewise NOT addressed here; a stronger
    serializability model is a separate cross-domain design change. Benefits configuration is immutable in-process,
    so there is no same-process rule race; across deployments the persisted snapshot freezes the outcome.
  - **Architecture:** `customer.order` may depend on Benefits only through `TransactionalBenefitsEvaluationPort`,
    `BenefitEvaluation` (and its nested types) and `BenefitsFailure`; the Order HTTP layer and Order metrics do not
    depend on Benefits or the snapshot; Checkout and Cart do not depend on Benefits; Order -> Membership stays
    forbidden. Three new ArchUnit rules (`ModuleBoundaryTest` 56 -> 59); the Benefits read-only-Membership rule gains
    the read-only `MembershipId.value()` accessor.
  - **Operational dependency (not a new gate):** Benefits-aware Order placement now depends on the Membership runtime
    path (Benefits reads Membership entitlement inside the placement transaction). The five existing Membership
    deployment gates therefore ALSO gate this path: no conflicting `memberships` collection, SchemaBootstrap
    privileges, identical plan configuration, no Mongo `readPreference` override, cluster default read/write concern.
    Ratified runtime consequence (mapping unchanged): Membership/Benefits `UNAVAILABLE` -> Order placement
    `UNAVAILABLE`; Membership integrity corruption reached through Benefits -> Order placement `INTEGRITY_FAILURE`
    (it fails closed and is never treated as "no benefit"). **Order placement is not deployment-ready until the
    Membership operational gates are verified.**
  - **Deployment gates unchanged and PENDING / UNVERIFIED:** the Order `orders`-count == 0 gate and the five
    Membership gates. Legacy Orders reconstruct without a snapshot, so this slice needs no backfill, but the existing
    Order gate stays until separately verified. Do not claim production readiness.

- **Checkout Benefits evaluation snapshot — internal advisory preview persisted with the quote**
  (`com.tazzzo.customer.checkout`, second Checkout + Order money-model slice): **COMPLETE** (PR #36, squash
  `30dea72482f71418048783b348647fdc0165a133`). *(Its public projection is the next slice, below.)* **Internal only: no
  public DTO/OpenAPI/HTTP change, no payable/final-total field, no Payment, no coupons, tax or fees, no production
  Benefits rule (0 configured).**
  - **What it does:** when Checkout creates a quote it evaluates Benefits through the STANDALONE
    `BenefitsEvaluationPort` (never the transactional port, no transaction of its own) over the quote's canonical
    merchandise subtotal and persists the result with the quote as `CheckoutBenefitSnapshot`. The evaluation happens
    after the final line set and subtotal are built and before the quote is persisted; nothing afterwards alters lines,
    quantities or subtotal. A forced persistence-transaction retry never re-evaluates (the evaluation precedes it).
  - **Advisory, not authoritative:** Order placement still re-evaluates Benefits transactionally and its
    `OrderBenefitSnapshot` is the authority. The two may disagree (e.g. preview `APPLIED`, Membership then revoked, Order
    `NO_MEMBERSHIP`; or preview `NO_RULE`, a later deployment configures a rule, Order `APPLIED`): the Order simply
    wins. No `BENEFIT_CHANGED`/`REQUOTE_REQUIRED` reason exists and Order never rejects because the Benefits outcome
    differs from the quote; `PRICE_CHANGED` stays specific to canonical Pricing authority. The preview is valid only as part
    of the quote snapshot and uses the quote's own `expiresAt` (no separate Benefits expiry, no Membership `validUntil`).
  - **`CheckoutBenefitSnapshot`** (Checkout-owned, immutable; Checkout never depends on Order's type): `NO_BENEFIT
    {eligibleSubtotalPaise, noBenefitReason: NO_MEMBERSHIP | NO_RULE | NOT_ELIGIBLE}` or `APPLIED
    {eligibleSubtotalPaise, discountPaise, discountBps}`, persisted as one additive nested `benefits` document on
    `checkout_quotes` (conditional presence, no placeholders). Membership/plan identity is deliberately NOT persisted.
    `eligibleSubtotalPaise` equals the quote's `subtotalPaise` (enforced by the `CheckoutQuote` constructor). The
    snapshot is projected from `BenefitEvaluation`; Checkout performs no Benefits arithmetic. The internal reason names
    (notably `NO_RULE`, a rollout/configuration state) are NOT exposed to clients and no public vocabulary is designed yet.
  - **Canonical Checkout money unchanged:** `unitPricePaise`, `lineTotalPaise`, `subtotalPaise`, `currency`, `itemCount`;
    no discounted unit price, net line, discounted subtotal or payable field; no line allocation.
  - **Idempotency / GET:** a replay of the same customer + `Idempotency-Key` and a GET return the stored quote and
    snapshot unchanged and never re-evaluate Benefits, even after a Membership or Benefits-configuration change.
  - **Legacy:** an ABSENT snapshot means a quote created before this slice (never "no benefit", never an error), so an
    old expired quote still answers 410 (never 500); no migration, no backfill, no index. A PRESENT snapshot is strictly
    reconstructed (explicit null, empty object, unknown outcome/reason, missing/foreign fields, wrong types, bad
    invariants, subtotal mismatch all fail loud -> the existing safe 500 `INTERNAL`).
  - **Failure (fail closed, never "no benefit"):** a Benefits outage -> quote creation fails with Checkout
    `UNAVAILABLE`; Benefits `INTEGRITY_FAILURE`/`INVALID_REQUEST` (or a result inconsistent with the quote subtotal) is
    an uncaught integrity defect -> the existing safe 500 `INTERNAL`. Normal no-benefit outcomes are successful
    quotes and record no failure metric. Metrics: no new Checkout metric or tag; the standalone Benefits/Membership
    layers keep their own failure counters.
  - **Architecture:** `customer.checkout` may depend on Benefits only through `BenefitsEvaluationPort`,
    `BenefitEvaluation` (and nested types) and `BenefitsFailure`; Checkout -> transactional port, Benefits
    implementations, Membership and Order stay forbidden; Cart stays out of Benefits; the Checkout HTTP layer and
    metrics do not depend on Benefits or the snapshot; no production code creates a quote without a snapshot. The
    previous blanket "Checkout and Cart do not depend on Benefits" rule is replaced by these narrower permanent rules
    (`ModuleBoundaryTest` 59 -> 62).
  - **Operational dependency (not a new gate):** Checkout quote creation now ALSO depends on the Membership runtime
    path through Benefits (as Benefits-aware Order placement does). The five existing Membership deployment gates (no
    conflicting `memberships` collection, SchemaBootstrap privileges, identical plan configuration, no Mongo
    `readPreference` override, cluster default read/write concern) therefore also gate Benefits-aware Checkout quote
    creation. A Membership/Benefits outage now fails quote creation closed (`UNAVAILABLE`).
  - **Deployment gates unchanged and PENDING / UNVERIFIED:** the Order `orders`-count == 0 gate and the five
    Membership gates. Do not claim production readiness.

- **Checkout Benefits preview — minimal public projection from the persisted advisory snapshot**
  (`com.tazzzo.customer.checkout`, third Checkout + Order money-model slice): **COMPLETE** (PR #37, squash
  `48380964edb18f35eef501eb5aaf495da95d5c32`). **Projection / API only:
  no Benefits calculation change, no Checkout persistence or evaluation change, no Order change, no payable/final-total
  field, no Payment, no coupons, tax, fees, coins or wallet, no production Benefits rule (0 configured).**
  - **One additive nested public object**, `benefitPreview`, on `CheckoutQuoteDto` / OpenAPI `CustomerCheckoutQuote`
    (not in `required`): `{ "applied": false }` for a modern quote whose Benefits evaluation applied no benefit, or
    `{ "applied": true, "discountPaise": <int64 >= 1>, "discountBps": <1..10000> }` for an applied one (conditional
    presence, no null placeholders; closed `oneOf`, `additionalProperties: false`). The three internal reasons
    (`NO_MEMBERSHIP`, `NO_RULE`, `NOT_ELIGIBLE`) ALL project to the identical `{applied:false}`; `NO_RULE` (a
    rollout/configuration state) and every other reason stay backend-only and no customer-facing reason vocabulary is
    designed. Not exposed: `membershipId`, `planId`, `planVersion`, `eligibleSubtotalPaise` (the quote's own
    `subtotalPaise` is that), Membership `validUntil`. Names deliberately avoid the catalog `discountAmountPaise` /
    `discountPercent`, which mean the MRP-vs-selling display discount.
  - **Legacy:** a quote created before Benefits preview (stored snapshot ABSENT) omits `benefitPreview` entirely; it is
    never synthesized as `{applied:false}` (`applied:false` means Benefits WAS evaluated and nothing applied). Old expired
    quotes still answer 410.
  - **Projection only:** the public values come ONLY from the stored `CheckoutBenefitSnapshot`, through a package-private,
    structurally pure `CheckoutBenefitPreview` projection owned by Checkout (it depends on no Benefits, Membership or Order
    class and cannot carry a reason, subtotal or identity); the DTO maps from that, never from the snapshot. No
    re-evaluation for the HTTP response, no Membership or rule read, no discount recomputation from the rate. A POST
    replay and a GET return the stored preview unchanged even after a Membership or Benefits-configuration change.
  - **Canonical money unchanged:** `subtotalPaise` stays the canonical merchandise subtotal and the preview does not make the
    quote a price lock or payable amount; no net/discounted/payable field exists and none is computed.
  - **Advisory:** OpenAPI states the preview is advisory, reflects the Benefits evaluation stored with the quote, and that
    Order placement re-evaluates Benefits authoritatively and may differ if Membership state or Benefits configuration
    changes before placement.
  - **Today's customer-visible effect:** with 0 production Benefits rules every modern quote shows `benefitPreview:
    {applied:false}` (non-member and member alike); this slice exposes the plumbing and creates no customer-visible
    discount until launch rules are configured.
  - **Contract tests:** the DTO/OpenAPI parity is pinned by `CheckoutQuoteContractTest`, which parses the authoritative YAML
    structurally and asserts: the DTO and schema property sets match; `benefitPreview` is optional (not in `required`); it
    is a closed two-shape `oneOf` (`additionalProperties: false` on both); the `applied` DISCRIMINATOR is `type: boolean`
    with `enum: [false]` in the not-applied shape and `enum: [true]` in the applied shape; the not-applied shape requires only
    `applied`; the applied shape requires `applied`, `discountPaise`, `discountBps`; `discountPaise` is `integer`/`int64`/
    `minimum: 1`; `discountBps` is `integer`/`minimum: 1`/`maximum: 10000`; and no internal reason or authority identifier is
    documented. This is in addition to the CI `swagger-cli` structural validation and the exact-JSON tests of both shapes
    and the legacy omission. (It does not run a JSON Schema validator over runtime responses.)
  - **Architecture:** one new rule freezes the projection as purely structural (62 -> 63); the existing rule that the
    Checkout HTTP layer/DTO/metrics do not depend on Benefits or the snapshot is unchanged and still holds. No metric
    added; no persistence change; no new runtime dependency.
  - **Deployment gates unchanged and PENDING / UNVERIFIED:** the Order `orders`-count == 0 gate and the five Membership
    gates (which also gate Benefits-aware Checkout quote creation and Order placement). Do not claim production readiness.

- **Order money snapshot — authoritative V1 payable model** (`com.tazzzo.customer.order`, fourth Checkout + Order
  money-model slice): **COMPLETE** (PR #38, squash `8051c45b7cf96dbc8d1374892d3b18281ff4a7b4`). **Order-domain only, internal first: no public API change, no Checkout change, no
  Benefits change, no Payment, no gateway, no Admin/CMS, no tax/GST engine, no fees, no coupons, no spendable Coins, no
  wallet, no production Benefits rule (0 configured).**
  - **Ratified V1 formula:** `payablePaise = merchandiseSubtotalPaise - benefitDiscountPaise`. Selling prices are treated
    as tax-INCLUSIVE for this calculation (there is no separate tax/GST computation); there are NO delivery, platform,
    handling, packaging, small-cart, surge or service fees; NO coupons; Tazzzo Coins are NOT spendable; there is NO wallet.
    These are V1 scope decisions, not claims that those concepts cannot exist later. None appears in the snapshot, not even
    as a zero placeholder (a zero would claim the component was evaluated and found to be zero); a later component is an
    explicit additive field and historical Orders are never recomputed.
  - **`OrderMoneySnapshot`** (Order-owned, immutable; not `OrderBenefitSnapshot`, not in Benefits or Checkout): components
    `merchandiseSubtotalPaise` and `benefitDiscountPaise`; `payablePaise()` is DERIVED, never caller-supplied, so an
    inconsistent payable cannot be constructed. Invariants: both `>= 0`, `benefitDiscountPaise <= merchandiseSubtotalPaise`,
    `payablePaise >= 0` by construction. **Zero payable is valid** (a 100% discount; no minimum payable); no rule was changed
    or configured. It implies no Payment: nothing is authorized, captured or collected, COD stays `COD`/`COD_DUE` unchanged.
  - **Sources (Order enforces them):** `merchandiseSubtotalPaise` is the Order's own canonical `subtotalPaise` (canonical lines
    after Pricing revalidation), never a client, Cart or Checkout-preview value; `benefitDiscountPaise` is the authoritative
    discount of the Order-side Benefits evaluation already used for `OrderBenefitSnapshot` (0 for no benefit), never recomputed
    from a rate and never taken from Checkout's advisory `benefitPreview`. The Order constructor rejects a money snapshot whose
    subtotal differs from the Order's, whose discount differs from the Benefit snapshot's, or that has no Benefit snapshot.
  - **Placement:** built in `OrderDraftAssembler` inside the existing placement `Tx.call`, after Pricing revalidation and the
    Benefits evaluation and before the Inventory reserve, and persisted by the same Order insert; no nested transaction.
    EVERY new Order (COD placement and the internal create-only path) persists one.
  - **Persistence:** one additive nested `money` document `{merchandiseSubtotalPaise, benefitDiscountPaise, payablePaise}` on
    `orders` (the same additive pattern as `benefits`); `payablePaise` is stored explicitly as the frozen fact and VERIFIED
    against the formula on reconstruction. **Absent = legacy (pre-money-model) Order**: it never means `payable = subtotal` and
    never `0`, and no historical amount is synthesized. A PRESENT snapshot is strictly reconstructed (missing/foreign fields,
    wrong BSON types, explicit null, negative or oversized discount, payable mismatch, subtotal mismatch, discount inconsistent
    with the Benefit snapshot, or money without its Benefit snapshot all fail loud). No migration, no backfill, no index, no
    modelVersion field (the explicit components plus the stored payable suffice; no repository precedent requires one).
  - **Replay / recovery:** a durable replay (fast path and in-transaction) and duplicate-key recovery return the stored Order and
    its stored money snapshot unchanged and never rebuild or re-evaluate it.
  - **Public API:** unchanged. `CustomerOrderDto`/OpenAPI do not expose `moneySnapshot`, `merchandiseSubtotalPaise`,
    `benefitDiscountPaise` or `payablePaise`; the existing `subtotalPaise` is unchanged. One new narrow ArchUnit rule keeps the
    Order HTTP layer and metrics independent of the snapshot (63 -> 64); Benefits, Checkout and Cart are already barred from
    `customer.order` by existing rules. No Checkout payable/money preview in this slice (see the Checkout advisory money slice).
  - **Deployment gate analysis:** the new field is additive and optional on reconstruction, so it adds no new requirement and
    no backfill; the existing `orders`-count == 0 gate (strict schema introduced earlier) neither covers nor is relaxed by it
    and stays PENDING / UNVERIFIED; no new gate is created. The five Membership gates (PENDING / UNVERIFIED) still gate
    Benefits-aware Order placement and Checkout quote creation. Do not claim production readiness.

- **Checkout advisory money snapshot + public `moneyPreview`** (`com.tazzzo.customer.checkout`, fifth Checkout + Order
  money-model slice): **COMPLETE** (PR #39, squash `f98aafb67d946c04a7a7141f164e1d0998ab759b`). **Checkout-side advisory money only: no Order change, no Benefits change, no Payment,
  no gateway, no tax/GST engine, no fees, no coupons, no spendable Coins, no wallet, no production Benefits rule (0
  configured).**
  - **Same ratified V1 formula as the Order:** `payablePaise = merchandiseSubtotalPaise - benefitDiscountPaise` (tax-inclusive
    prices; no fees/coupons/Coins/wallet; none of them appears, not even as a zero placeholder). Zero payable is valid.
  - **`CheckoutMoneySnapshot`** (Checkout-owned, immutable; deliberately NOT `OrderMoneySnapshot`, because Checkout is advisory
    and Order is authoritative and Checkout never depends on Order): components `merchandiseSubtotalPaise` and
    `benefitDiscountPaise`; `payablePaise()` is derived. Invariants: both `>= 0`, discount `<=` subtotal. `CheckoutQuote` enforces
    that the subtotal is the quote's own `subtotalPaise`, the discount is the STORED advisory `CheckoutBenefitSnapshot` discount
    (`Applied.discountPaise`, 0 for no benefit; never a rate recomputation and never a second Benefits call), and that a money
    snapshot requires the Benefits snapshot.
  - **Creation:** built in `CheckoutService.candidate` right after the Benefits snapshot and before the single quote persist;
    no extra write, no nested transaction. Every new quote persists both snapshots.
  - **Persistence:** one additive nested `money` document `{merchandiseSubtotalPaise, benefitDiscountPaise, payablePaise}` on
    `checkout_quotes`; `payablePaise` is stored explicitly and VERIFIED against the formula on reconstruction. **Absent = a quote
    created before this model** (benefits absent + money absent, or benefits present + money absent): never `payable = subtotal`,
    never `0`, nothing is synthesized. Money present with Benefits absent, or disagreeing with them, is invalid and fails loud
    (safe 500 `INTERNAL`).
  - **Replay / GET:** return the stored quote and its stored money unchanged; no Benefits evaluation, no recalculation.
  - **Advisory vs authoritative:** the Order computes its own money and may legitimately differ after a Membership,
    Benefits-configuration or Pricing change (tested: Checkout payable 9500 stays stored while the Order is 10000). There is no
    `PAYABLE_CHANGED`/`BENEFIT_CHANGED`/`QUOTE_CHANGED` behaviour and the Order does not honour the Checkout payable.
  - **Public API (additive):** one optional nested `moneyPreview {merchandiseSubtotalPaise, benefitDiscountPaise, payablePaise}`
    on `CustomerCheckoutQuote` (closed, all three `int64`, `minimum: 0`; absent on an older quote), alongside the unchanged
    `benefitPreview` and top-level `subtotalPaise`. The OpenAPI text says it is advisory, not Payment authority, not a price lock,
    and that the Order may differ. The DTO consumes only the public-safe `CheckoutMoneyPreview` projection.
  - **Architecture:** two new rules (64 -> 66): the Checkout HTTP layer and metrics do not depend on `CheckoutMoneySnapshot` or its
    codec, and no production code creates a quote through the Benefits-only (money-less) constructor.
  - **Deployment gates unchanged and PENDING / UNVERIFIED:** the Order `orders`-count == 0 gate and the five Membership gates.
    No new gate. Do not claim production readiness.

- **Benefits static config hardening — plan cross-validation + config consistency** (`com.tazzzo.benefits`,
  `com.tazzzo.wiring`, sixth Checkout + Order money-model slice): **COMPLETE** (PR #41, squash `22416e7a90aa36bc557d0926cc4b44dd7007a6b4`). **Configuration boundary only: no
  runtime evaluation change, no customer API change, no Order/Checkout semantics change, no Admin API, no Mongo-backed
  Benefits rules, no Payment, no gateway, no coupons, no tax/fees/Coins/wallet. Production Benefits rules remain 0 (no
  launch percentage, minimum subtotal, 100%-discount policy or cap is ratified).**
  - **V1 model preserved:** Benefits stays STATIC configuration (`tazzzo.benefits.rules`): process-local, immutable after
    startup, no reload or runtime mutation. Rule identity is `(planId, planVersion)`, one rule per Membership plan version.
    A rule is immutable for its `(planId, planVersion)`: changing the discount rate or minimum subtotal for a commercial
    plan means a NEW Membership plan version (Git/deployment governs static config evolution; there is no runtime
    comparison with historical deployments).
  - **Orphan rules fail startup.** `BenefitsConfig` validates every configured rule against a Benefits-owned port
    (`BenefitPlanCatalog`) while building the rule source; `com.tazzzo.wiring.BenefitPlanCatalogConfig` answers it from the
    already-loaded Membership plan CONFIGURATION (`MembershipPlanSource`: no Mongo read, no entitlement lookup). A rule whose
    `(planId, planVersion)` is not a configured plan fails the application start with a message naming only the planId and
    planVersion (no commercial values); it is never ignored, dropped, turned into `NO_RULE` or re-pointed at another version.
    Benefits still depends on no Membership implementation class (the composition layer is the only place that sees both).
  - **Unchanged and still valid:** zero rules; a plan or plan version WITHOUT a rule (evaluates to `NO_RULE`); several rules for
    distinct configured plan versions. The existing duplicate-rule and malformed-rule startup failures are unchanged.
  - **Startup visibility:** one INFO line reports the configured rule COUNT only (no rule identity, no commercial value).
  - **Config consistency:** the existing identical-configuration deployment gate now explicitly covers BOTH
    `tazzzo.membership.plans` AND `tazzzo.benefits.rules` on every application instance and environment (instances with
    different static rules would produce different Checkout/Order outcomes). This clarifies the SAME gate; the total stays six
    (the `orders`-count gate plus the five Membership gates), all PENDING / UNVERIFIED. This PR reduces config mistakes; it does
    not introduce any `PAYABLE_CHANGED`/`BENEFIT_CHANGED`/`QUOTE_CHANGED` behaviour, and Order does not honour the Checkout payable.
  - **Admin:** none added. Current admin security (two static shared service tokens, no per-person identity, no fine-grained
    authorization, no actor-attributed audit) is insufficient for mutable Benefits; that is a separate prerequisite.
  - **Documentation cleanup carried from PR #39:** Checkout Advisory Money moved to COMPLETE; Last verification refreshed; the
    Checkout quote Javadoc and the `customer-checkout` OpenAPI tag description no longer describe Checkout only as "not a final
    payable total" (they say `moneyPreview` exists, is ADVISORY, and Order is AUTHORITATIVE; the stale "no order endpoints exist
    yet" sentence was corrected). Description-only: no OpenAPI schema or DTO change.
  - **Architecture:** no new rule (66 stay green); Benefits remains decoupled from Membership internals.

- **PR-21 — binding payable contract (PR #40, squash `3839f3d26e94f7d6cfed6e0da098ed900fd95a04`): MERGED WITHOUT
  RATIFICATION; its binding semantics are REMOVED by the forward fix below.** History, recorded as fact: PR #40 made the quote's
  `moneyPreview` BINDING (Order placement had to reproduce it exactly, in either direction, or was refused with a new 409
  `PAYABLE_CHANGED`; a legacy quote without money was refused too), rewrote the Checkout/Order documentation and Javadoc from
  "advisory" to "binding", annotated the earlier money-model entries here as superseded, and added a source-scan test banning
  the word "advisory" from Checkout/Order sources. It ALSO added a useful, independent public projection of the Order's
  authoritative money (`CustomerOrderDto.money`) and documented the previously undocumented POST-quote replay-after-expiry 410.
  An independent architecture review classified the binding behaviour as an unratified architecture change (the ratified model
  is: Checkout money ADVISORY, Order money AUTHORITATIVE, they may differ, no `PAYABLE_CHANGED`). The forward fix keeps the
  public Order money (hardened) and the 410 documentation and removes everything binding.

- **Forward fix — restore advisory Checkout money, keep public Order money** (`com.tazzzo.customer.order` +
  `customer.checkout` documentation): **COMPLETE** (PR #42, squash `89fcf349c24d67dd610c28eafa34d2e70669195a`; merged-`main`
  push CI `37039217513`: 2250 tests, 0 failures / 0 errors / 0 skipped, `ModuleBoundaryTest` 67/67). **No Payment, no gateway, no new money component, no Benefits change, no
  change to persisted Checkout or Order money, no production Benefits rule (0 configured).**
  - **Removed (PR #40's unratified binding contract):** `OrderFailure.PAYABLE_CHANGED`, its 409 mapping and public error code,
    the Checkout/Order money-equality check in `OrderDraftAssembler` (Order placement no longer reads the quote's money at all),
    the refusal of quotes without money, all binding wording in Javadoc and OpenAPI, the binding/refusal tests, the
    `TestQuotes` helper and the source-scan test that banned the word "advisory".
  - **Restored (ratified model):** Checkout Benefits and Checkout money are ADVISORY; Order Benefits and Order money are
    AUTHORITATIVE; they may differ and the Order wins, in BOTH directions (a benefit lost OR gained after the quote, a Benefits
    configuration change, a zero payable appearing or disappearing). A legacy quote without money places normally. Order
    placement still revalidates Pricing (`PRICE_CHANGED` is unchanged and independent), Membership, Benefits, Inventory,
    serviceability and the address. Replay, in-transaction replay and duplicate-key recovery still return the stored Order.
    The end-to-end disagreement test (quote 10000/500/9500, Membership revoked, Order 10000/0/10000 succeeds) is restored.
  - **Kept and hardened (public Order money):** optional `CustomerOrder.money {merchandiseSubtotalPaise, benefitDiscountPaise,
    payablePaise}` (closed OpenAPI schema `CustomerOrderMoney`, all `int64`, `minimum: 0`), ABSENT on a legacy Order (never a
    zero payable), the commerce amount owed (`COD_DUE`: due on delivery), never a payment fact. The DTO now reads it through a
    new package-private public-safe projection `OrderMoneyView` (`Order.moneyView()`), the Order counterpart of Checkout's
    `CheckoutMoneyPreview`, and no longer takes `OrderMoneySnapshot` directly.
  - **Architecture:** the Order HTTP/metrics money rule now also selects types NESTED in a controller/handler/DTO/observability
    class (the nested `CustomerOrderDto.OrderMoney` record had bypassed the suffix-only selector), and a new rule forbids
    `customer.order` from depending on Checkout's advisory money types (`CheckoutMoney*`; widened to `CheckoutBenefit*` by the
    boundary hardening below). `ModuleBoundaryTest` 66 -> 67.
  - **Deployment gates unchanged:** the Order `orders`-count == 0 gate and the five Membership gates (the plan/rule identical-config
    gate covers `tazzzo.membership.plans` and `tazzzo.benefits.rules`) remain PENDING / UNVERIFIED; no new gate.

- **Checkout/Order architecture boundary hardening** (`ModuleBoundaryTest` only): **COMPLETE** (PR #43, squash
  `0dd83b51d9fd74f17960c769648a227ed0ee89d0`; merged-`main` push CI `37045625143`: 2250 tests, 0 failures / 0 errors / 0 skipped,
  `ModuleBoundaryTest` 67/67). **Architecture tests and
  documentation only: zero production files changed, no runtime, API, persistence or transaction change, no Payment, no Admin.**
  - **Order never consumes Checkout's advisory outputs:** the rule `order_does_not_depend_on_checkout_advisory_money` is widened
    and renamed `order_does_not_depend_on_checkout_advisory_benefits_or_money`: `customer.order` may not depend on any
    `customer.checkout` type named `CheckoutMoney*` (snapshot, codec, preview) OR `CheckoutBenefit*` (snapshot, codec,
    preview). Legitimate quote dependencies (`CheckoutQuote`, its lines, `CheckoutQuoteId`, the quote repository) stay allowed;
    no package-wide ban.
  - **Nested types can no longer bypass HTTP-surface rules:** every rule that SELECTS public HTTP/metrics classes by simple-name
    suffix now uses the nested-aware selector introduced for the Order money rule (`selfOrEnclosingSimpleNameEndingWithAny`):
    the two Checkout rules (Benefits snapshot, money snapshot), the Order Benefits-snapshot rule, the Order HTTP-delegation rule,
    the create-only-path controller rule, `http_layer_never_depends_on_membership` and
    `customer_controllers_cannot_access_membership`. (`membership_has_no_http_surface` and `benefits_has_no_http_surface` use the
    suffix as a CONDITION, not a selector, so a nested type cannot bypass them; they are unchanged.)
  - **Nested types can no longer bypass snapshot TARGETS either:** `CheckoutBenefitSnapshot` and `OrderBenefitSnapshot` are sealed
    interfaces whose records are nested (`Applied`, `NoBenefit`), so a prefix match on the simple name missed a dependency on, e.g.,
    `CheckoutBenefitSnapshot.Applied`. Every snapshot target (Checkout/Order Benefits and money snapshots, and the Order rule's
    `CheckoutMoney*`/`CheckoutBenefit*` targets) and the `CheckoutBenefitPreview*` projection selector now use the matching
    nested-aware helper (`selfOrEnclosingSimpleNameStartingWithAny`). `ModuleBoundaryTest` stays 67 (one rule widened and
    renamed, none added or removed).
  - **Proof (scratch mutations, all restored):** Order depending on `CheckoutMoneySnapshot` or on `CheckoutBenefitSnapshot.Applied`,
    a nested `CustomerOrderDto` type consuming `OrderMoneySnapshot`, and nested `CheckoutQuoteDto` types consuming
    `CheckoutMoneySnapshot` / `CheckoutBenefitSnapshot` each FAIL `ModuleBoundaryTest` on the intended rule. With `main`'s previous
    rules the nested Checkout DTO mutations and the `CheckoutBenefitSnapshot.Applied` mutation PASS (the closed blind spots), and with
    the widened Order rule removed the two Order mutations PASS (the rule is non-vacuous).
  - Production Benefits rules remain 0; the six deployment gates remain PENDING / UNVERIFIED; Payment remains deferred.

- **Admin principal + actor-attributed audit foundation** (`com.tazzzo.common.audit`, `com.tazzzo.admin.auth`, the INTERNAL
  `/api/**` controllers and their catalog services): **COMPLETE** (PR #44, squash
  `2e8d87bd59bbb1d81c355b43b083694e6b928cc0`; merged-`main` push CI `37060802238`: 2283 tests, 0 failures / 0 errors / 0 skipped,
  `ModuleBoundaryTest` 70/70). **No per-person login, no OIDC/JWT/passwords, no admin
  credential or user collection, no central audit collection, no new endpoint or OpenAPI change, no Benefits/Membership/
  Pricing admin API, no Payment. Production Benefits rules remain 0.**
  - **Neutral `Actor`** (`common.audit`): `type` (`HUMAN_ADMIN` | `SERVICE_ACCOUNT` | `SYSTEM`), stable `id`, optional
    non-secret `credentialId`, and `requestId` (required for request-borne actors, absent for `SYSTEM`, whose ids are
    `system:<name>`). No display name, email, permissions, IP or user agent. `common` never depends on `admin` (ArchUnit).
  - **`AdminPrincipal` + `AdminPrincipalResolver`** (`admin.auth`, mirroring `CustomerPrincipal`/`CustomerPrincipalResolver`):
    `ApiAuthFilter` now attaches a typed principal instead of the unused `auth_role` string. The two shared tokens map to explicit
    SERVICE_ACCOUNT principals: `service:cms-writer` (credential `shared-token:cms-writer`, role `cms-writer`) and `service:reader`
    (`shared-token:reader`, role `reader`). Token material is never stored, logged or audited. Coarse authorization is unchanged
    (reader GET only, cms-writer read + write, unset token disables its role, UNKNOWN route 404 before credentials), and so is the
    existing precedence when both tokens are configured identically (cms-writer wins; now pinned by a test).
  - **Explicit actor propagation:** every admin mutation (products: create/mint, bundle, variant pack, patch title, classify,
    publish claim, GTIN bind, activate/discontinue/revive/archive, merge; taxonomy: open/publish release, rename/move/merge/split/
    deprecate/revive node; attributes: create definition, add enum value, add schema field; evidence: create, retract) takes an
    `Actor` as its first parameter, built ONLY from the authenticated principal plus the server request id (`AdminActors`); the
    request body and headers cannot choose it. No ThreadLocal or static holder.
  - **Fail-closed admin boundary (review hardening, MEDIUM-1):** every public admin service method that takes an `Actor` (the 25
    controller-invoked entry points plus `BundleService.activate`, the batch `activateRelease` overload and `recordBaseline`: 28)
    rejects a `null` actor with `Objects.requireNonNull(actor, "actor")` as its FIRST statement, before any transaction, event or
    write. The generic event infrastructure (`EventPayload`, `DomainEvent`, `ActorDocuments`) deliberately keeps the actor optional
    for legacy and non-admin events. `AdminActorFailClosedIT` invokes all 28 entry points with a null actor and proves nothing is
    written anywhere, proves valid product/taxonomy/attribute/evidence calls fail closed with `null` and succeed with an actor, and
    proves an HTTP title PATCH is attributed to `service:cms-writer` with its request id.
  - **Transactional actor attribution on the existing ledgers:** `EventPayload`, `product_events`, `node_events`, `price_events`
    (when the caller supplies an actor through the new `PricingService.upsertPrice(cmd, actor)`) and `DomainAudit`/`domain_events`
    carry an optional `actor: {type, id, credential_id?, request_id?}` written in the SAME transaction as the mutation (event-first
    preserved); an event-write failure rolls the mutation back (tested), and the actor survives a transaction retry unchanged
    (tested). Every product/taxonomy/attribute/evidence admin mutation's events carry the actor (its `classification_history`,
    `evidence_links`, `work_queue` and registry side-writes are audited through those same `product_events` rows).
  - **SYSTEM actors** for background work in the touched services: `system:taxonomy-stamp-worker`, `system:merge-finalizer`,
    `system:taint-worker`. Paths with no audited caller yet (inventory, media, rollups, offers, canonical-key backfill, the
    unattributed `upsertPrice(cmd)`, serviceability's `DomainAudit` use) still write events WITHOUT an actor: unattributed, never a
    guessed identity.
  - **Legacy compatibility:** an event without `actor` stays valid and means historical/unattributed; a PRESENT actor is read
    strictly (`ActorDocuments`: unknown type, missing/blank/non-string field, foreign key or explicit null fails loud). No migration,
    no new collection, no new index; actor querying is out of scope.
  - **Observability:** `admin_auth_rejected{reason=unauthenticated|forbidden}` (bounded tag only) plus a bounded warning log with
    the request id; no token, header or customer data is logged.
  - **Architecture:** `common_audit_never_depends_on_admin_authentication`,
    `admin_authentication_depends_on_neither_customer_auth_nor_catalog` and
    `customer_authentication_never_depends_on_admin_authentication` (`ModuleBoundaryTest` 67 -> 70).
  - **What this does and does not establish:** every audited admin mutation made through the shared tokens is attributed to a
    named SERVICE_ACCOUNT principal and its request id. It does NOT identify WHICH PERSON made a change: per-person admin
    authentication (identity provider or per-person credentials), CMS hosting/CORS and finer-grained sensitive permissions are
    later slices. The CMS is still not ready for broad human administration of sensitive areas. (Per-person authentication
    follows in "Human Admin Google OIDC + config allowlist", below.)
  - **Deployment gates unchanged:** six, all PENDING / UNVERIFIED; no new collection, index or gate.

- **Human Admin Google OIDC + config allowlist** (`com.tazzzo.admin.auth`, `ApiAuthFilter`): **COMPLETE** (PR #45, squash
  `d018cac373c0461fb0d4c7eaa56a425a883f7b3f`; merged-`main` push CI `37076192370`: 2366 tests, 0 failures / 0 errors / 0 skipped,
  `ModuleBoundaryTest` 73/73). **HUMAN_ADMIN
  authentication is added. No CMS UI or BFF, no login/callback/authorization endpoint, no `/me`, no CORS, no cookies, no backend
  admin session, no token/refresh-token/session persistence, no admin user or credential collection, no Google client secret,
  no new endpoint and no OpenAPI change. Sensitive Admin modules (Pricing, Membership, Benefits, Coins/Wallet) remain blocked;
  Payment is not started; production Benefits rules remain 0.**
  - **Two credential families, one principal, two stages.** Stage A (authentication, `AdminAuthenticatorChain`): the exact shared
    service token first (`ServiceTokenAuthenticator`), and only if it does not match, Google OIDC (`GoogleOidcAuthenticator`); no
    routing by token shape (`contains(".")`/segment count). Stage B (authorization): the unchanged coarse role check (reader GET
    only, cms-writer read + write) for every family. `ApiAuthFilter` attaches the same typed `AdminPrincipal` either way;
    `AdminPrincipalResolver`, `AdminActors` and every admin service are unchanged and never learn how the caller authenticated.
  - **Service tokens remain supported and unchanged:** `service:cms-writer` / `service:reader`, unset token disables its role,
    identical tokens still resolve to cms-writer; the comparison is now constant-time (`MessageDigest.isEqual`).
  - **Google ID-token verification** (Nimbus JOSE+JWT 9.37.3; local cryptographic verification, never Google's `tokeninfo`
    endpoint): JWS `RS256` only (`alg=none`, HS256 including the public-key confusion attack, and RS512 are refused); the key is
    selected by `kid` from Google's published JWKS (`https://www.googleapis.com/oauth2/v3/certs`; cached 5 min, refreshed on an
    unknown `kid` for rotation, rate-limited, retried; unavailable keys or an unknown `kid` after refresh fail CLOSED; no key is
    committed to source); issuer `https://accounts.google.com` or Google's documented legacy `accounts.google.com`, nothing else;
    exactly ONE audience, the configured one (a multi-audience token is refused even when it lists the admin client), and an
    authorized party `azp`, when present, equal to that audience (review hardening LOW-1: a token issued for a different
    client is refused; Nimbus' audience-membership check is kept as defense-in-depth); `exp`/`nbf` with 60 s skew; `iat` no more than 60 s in the future; `hd` EXACTLY the configured
    Workspace domain (absent, e.g. a personal account, is a mismatch; the email suffix is never consulted); `email_verified`
    the JSON boolean `true` only (review hardening NOTE-1: string/numeric forms are refused); non-blank `sub`. Only `sub` leaves the verifier.
  - **Identity and roles:** actor `HUMAN_ADMIN`, id `google:<sub>` (never the email; an email change keeps the identity),
    credential id `oidc:google:<credential-label>` (short, non-secret). Roles come ONLY from the backend allowlist
    `tazzzo.admin.users` (keyed by provider + subject); Google claims never grant roles. A valid in-domain identity that is not
    allowlisted, or is allowlisted with `enabled: false`, gets no principal.
  - **Status contract:** 401 `UNAUTHENTICATED` (one uniform body: no oracle for which check failed) when the token does not
    satisfy the admin identity trust policy: `invalid_token` (malformed, bad signature, unknown key, wrong algorithm, issuer or
    audience, future `iat`, unavailable keys), `expired_token`, `domain_mismatch`, `email_unverified`. 403 `FORBIDDEN` when
    identity is proven but admin access is not granted: `not_allowlisted`, `disabled`, and (unchanged) `forbidden` for a reader
    write. The UNKNOWN surface still answers 404 before any credential is judged.
  - **Configuration (all-or-nothing):** `tazzzo.admin.oidc.{issuer, audience, hosted-domain, credential-label}` (+ optional
    `jwks-uri`, https only) and `tazzzo.admin.users[n].{provider, subject, email, roles, enabled}`. Nothing set: human OIDC is
    DISABLED and the backend runs on the service tokens exactly as before. Any OIDC value set: all four are required. Users
    without OIDC, a non-Google issuer, a malformed domain or label, a blank/duplicate subject, an unknown provider or role, an
    empty role set or a blank/malformed email label fail startup; the failure message never prints a subject or email, and the
    startup log reports only enabled/disabled and the allowlist size. All of these values are non-secret.
  - **Observability:** `admin_auth_rejected{reason}` is extended with the closed reasons `invalid_token`, `expired_token`,
    `domain_mismatch`, `email_unverified`, `not_allowlisted`, `disabled` (plus `unauthenticated`, `forbidden`); never a sub, email,
    actor id, request id, token, kid, issuer or audience. Warning logs carry only the reason and request id (a reader-write
    refusal logs the actor TYPE, no longer the actor id); no token, header, claims, email or JWKS body is logged.
  - **Audit:** proven end to end over HTTP (locally signed token -> `ApiAuthFilter` -> `AdminPrincipal` -> service -> event
    ledger): `actor: {type: HUMAN_ADMIN, id: google:<sub>, credential_id: oidc:google:<label>, request_id: <response X-Request-Id>}`;
    no email, name, picture, `hd`, claims or token material is persisted. `Actor` and `AdminPrincipal` schemas are unchanged.
  - **Architecture:** `jose_jwt_library_is_confined_to_admin_authentication`,
    `google_oidc_implementation_is_internal_to_admin_authentication`, `admin_principal_is_provider_neutral`
    (`ModuleBoundaryTest` 70 -> 73).
  - **What this establishes:** Tazzzo backend can authenticate allowlisted human Admins through verified Google Workspace OIDC
    identity and attribute audited mutations to a stable HUMAN_ADMIN subject. It does NOT make the CMS login experience complete:
    the BFF/UI integration is not part of this slice. Next slice (not started): Admin `/me` + CMS BFF integration (Google
    login/callback in the BFF, secure HttpOnly session cookie, CSRF protection, no browser token storage, humans moved off the
    shared cms-writer token, cms-writer rotated for machines only).
  - **Before any sensitive Admin module is exposed** (Pricing, Membership, Benefits): CMS human login integration, humans off the
    shared cms-writer token, module-specific sensitive write permissions, an audit read path, verified deployment gates, and
    (for Pricing) Pricing LOW-1 fixed: the unattributed `PricingService.upsertPrice(cmd)` overload must give way to an
    actor-required pricing path. LOW-1 is carried forward unchanged here.
  - **Unresolved external values:** the production admin OAuth client id (audience), the company Workspace hosted domain, the
    credential label and the allowlisted Google subjects with their roles.
  - **Deployment gates unchanged:** six, all PENDING / UNVERIFIED; the identical-configuration gate (3) now also covers
    `tazzzo.admin.oidc.*` and `tazzzo.admin.users`. No new gate, collection, index or migration; `SchemaBootstrap` unchanged.

## In review (NOT merged)

- **Asynchronous product import jobs** (branch `feature/async-import-jobs`, from `main` `7d491dd`): **IN REVIEW**. The synchronous product import
  (500 rows, request held open, whole-file reject) cannot load a catalogue of tens of thousands of SKUs. `bulkimport.jobs` adds a job engine:
  `POST /api/v1/admin/imports/jobs`, rows streamed in as RFC 4180 CSV (the CMS wizard's column aliases, typed attribute cells) or JSON in any
  number of requests, a leased background worker (`ImportJobScheduler`, gated by `tazzzo.scheduler.enabled` AND `import-jobs-enabled`) that
  validates and applies in persisted 500-row batches from a cursor, explicit approval (`apply`) recorded as `approvedBy` and attributing every
  mint, `PAUSED`/`resume` on a datastore failure without re-applying, `cancel` (noticed before the next mint), per-row verdicts (`GET rows`,
  `errors.csv`), DB-enforced one row per product id / GTIN / internal key per job (two partial unique indexes, across appends and batches), one
  upload at a time per job, CAS on `version` for every admin transition, `domain_events` audit of each admin transition. Each applied row's verdict,
  the cursor and the counters are recorded in one lease-guarded transaction after the mint (validation batches and the datastore pause likewise),
  so a lost lease or a cancel can never leave counts and verdicts disagreeing (independent review of the first head found exactly that; fixed).
  Second independent review (2026-10-08, NEEDS-CHANGES, 0 HIGH / 4 MEDIUM) — all fixed: the pause path wrote without the lease; an apply-time
  UNCHANGED row was counted nowhere (now `valid`/`unchanged` are reclassified so a COMPLETED job has `applied + failed == valid`); validation could
  start during an upload (now refused while the append lock is held; `rows_total` is only raised while OPEN under the lock, `page` never reads
  past it, an abandoned upload's rows are removed); a mint could outlive the lease (lease default 120 s → 300 s, above the driver's transaction
  retry budget; an identity collision is re-checked and recorded UNCHANGED when the product is the row's own). Also fixed while testing: lease
  ownership was judged by `getModifiedCount()`, so a renewal in the same millisecond as the previous lease write was misread as a lost lease
  (now `getMatchedCount()`); LOWs fixed: CSV column cap enforced while reading, GTIN-identity rows no longer claim an internal-key slot,
  `recordApply` returns through `Tx.call`. Six mutants re-introducing these bugs are each killed by `ImportJobsIT`. V0016's checksum changed
  with its fifth index; V0016 has only ever run in tests (the branch is unmerged and nothing is deployed), so no environment needs a re-pin.
  Third independent review (of `cafcaf2`, NEEDS-CHANGES) — fixed: re-validating a REJECTED job cleared verdicts AFTER the job became claimable,
  racing the worker (verdicts are now never bulk-cleared: each pass re-verdicts every row from row 0); the collision re-check's "is it the row's
  own product" guard had no killing test (added); an upload read `rows_total` before its lock (now re-read under it); the append lock now dies
  with every state change. Known limit kept: rows of an upload whose process died stay behind and block later uploads to that job (cancel it).
  `ProductImportValidator.validateRows` is the per-row (non-throwing) form of the same checks; the synchronous import now delegates to it and is
  otherwise unchanged. Collections `import_jobs`/`import_rows` and five indexes via migration `V0016` (`MigrationRegistryTest` pins the checksum;
  `IndexContractIT`, `DatastorePrivilegeIT` 16 applied, `DatabaseDocsConsistencyTest` updated with the retention/inventory/manifest/runbook rows).
  Lease `import-jobs-lease-ms` 300 s. Limits: `tazzzo.imports.max-rows-per-job` 250,000, `max-active-jobs` 10, request chunk bounded by the bulk body limit (2 MiB ≈ 20k CSV rows).
  Evidence: `ImportJobsIT` (16 scenarios incl. a 621-row multi-batch file, pause/resume exactly-once, cancel-while-minting, lease lost mid-apply,
  cross-append identities, stale versions/limits/expired leases/append lock, pause after a lost lease, collision re-check, same-ms renewal,
  validation during an upload), `ImportCsvParserTest`; `docs/ops/BULK_IMPORT.md`.
  Not done: price/stock job kinds, purge policy for old jobs, a CMS screen for jobs, canonical-identity duplicates across batches (apply-time FAILED).

- **HTTP error-handling hardening** (branch `fix/http-error-handling-hardening`, from `main` `d790504`): **IN REVIEW**. The 12 controller-scoped `/v1` advices with an
  `Exception` catch-all (OTP, session, profile, address, account deletion, customer support, delivery slots, cart, checkout, order, commerce read, public content;
  staff support falls through to `ApiExceptionHandler`) no longer turn framework request-shape failures into a logged-as-ERROR 500. `catalog.api.ClientRequestErrors`
  classifies them once (unreadable body or missing/mistyped binding → 400, unsupported or absent `Content-Type` → 415, unacceptable `Accept` → 406). Each advice answers in
  its own documented shape with a code from its existing documented set: no new error codes, and no OpenAPI or route change. Every error body's `Content-Type` is fixed to JSON, so an `Accept`
  header can no longer turn an error into a 500 or an empty-body response. Authentication still runs first (401 before any body is read). Evidence: `MalformedRequestErrorMappingIT`
  (15 HTTP tests, one per advice plus auth/correlation boundaries), `ClientRequestErrorsTest` (classification, negatives, and a structural guard that every catch-all advice consults
  the classifier), 9/9 mutations detected. Not changed: the ERR-1 collapse of framework 405/415/406 to `400 INVALID_REQUEST` on unmatched public routes, and the admin/consumer
  advices without a catch-all (see the PR's remaining findings).

- **Database foundation (MongoDB, DB-0 … DB-4)** — authoritative documents in [`docs/database/`](database/):
  - **DB-0** database inventory: **COMPLETE** (PR #47, `52ab530`). **DB-1** collection contracts + validator policy: **COMPLETE** (PR #48, `e3a0db6`).
    **DB-2** index manifest, per-vertical cursor index, `IndexContractIT`: **COMPLETE** (PR #50, `f99c1fe`). **DB-3** versioned, locked migration
    framework (V0001–V0007, including the nine audit-read indexes), dry run, target guard, safe startup modes (R3, R5): **COMPLETE** (PR #51, `d9c440f`).
  - **DB-4** staging requirements + users/security: **COMPLETE IN CODE** (PR #52, squash `4b27f32`; Atlas staging is NOT yet connected or verified). It adds the
    explicit connection contract for staging/production (TLS, retry, `w=majority`, read concern, timeouts, pool: closes risk R7; the numeric limits are **PROPOSED — owner ratification required**), a fail-fast datastore verifier
    that runs before the migration runner, a readiness gate so no `@Scheduled` worker acts until the datastore is verified and startup has finished (a refused process or a migration job runs none), an environment label that is metadata rather than a boundary
    (a remote, proxied, wildcard or ambiguously spelled target is enforced whatever it is called; one strict classifier drives the verifier and `TargetGuard`), V0001's checksum input frozen so future schema cannot change a released migration, three least-privilege identities (runtime / migrator / read-only dry run) generated from one model
    and proven against a real authenticated replica set, the shipped role files (`docs/database/roles/`), the staging runbook, and a retention matrix + PII map
    for all 49 collections. The runtime identity holds no schema authority and cannot write the migration history; a migrator connecting as the runtime is refused.
  - **Honest state:** Atlas staging is **PARTIAL** (design decisions only: no cluster, users, URI or connectivity); a staging dry run is **blocked on infrastructure**, not on code;
    nothing was run against Atlas, AWS or production. DB-5 (ingestion), DB-6 (backup/restore/retention), DB-7 (query/load verification) and DB-8 (readiness gate) are not started.
    The six deployment gates stay **PENDING / UNVERIFIED**. **The production datastore is NOT READY.**
  - **R1 — FIXED IN CODE** (price-history retention PR): `price_events` is append-only history. `RollupService.purge()` and its hourly call are deleted; nothing in `main` deletes or expires a ledger row
    (pinned by `PriceHistoryRetentionSourceTest`; no TTL index). The roll-up now aggregates ONLY legacy offer events (string `product_id` and `seller`, int32 `price`) into `price_rollups`, claims each with a conditional
    `rolled=true` flag in the same transaction (exactly-once, restart-safe, safe under overlapping runs) and never reads or writes a paise ledger row, so the old null/invalid aggregate for paise rows is gone. No migration.
    `TAZZZO_SCHEDULER_ENABLED=false` is **no longer required because of R1**; other scheduler blockers (projection/freshness/reservation-expiry flags, real-Atlas proof) are separate. Still to verify on a real staging database.
    No price WRITER is exposed over HTTP yet (a downstream pricing-admin PR); `PricingService.upsertPrice(cmd)` (unattributed overload) still exists, has no caller outside tests, and is a carried LOW.
  - **PRE-EXISTING DEFECT — OUTSIDE THE DATABASE FOUNDATION:** `GET /api/v1/products` without `canonicalKey` answers a generic 500 (the route's request-parameter condition is
    unsatisfied and is mapped to `INTERNAL`). Reproduced at an older head, unrelated to the audit-read API; to be fixed separately, not in any DB PR.

- **Admin `/me` CMS bootstrap identity** (`GET /api/v1/admin/me`): **COMPLETE** (PR #46, merged `f5b2cdd`, CI run 37081156657, 2382 tests, ModuleBoundaryTest 73/73). **The backend exposes a safe authenticated
  Admin bootstrap endpoint for the CMS; the CMS login flow is NOT complete (no `tazzzo-web` BFF exists). No login/callback/
  logout, sessions, cookies, PKCE/nonce/CSRF, CORS, audit read API, sensitive Admin module or Payment.**
  - **Endpoint:** INTERNAL surface (`SurfaceClassifier`), authenticated by the unchanged `ApiAuthFilter`; any authenticated
    admin principal may call it (human or service account, reader or cms-writer: it is a GET). Missing/invalid credentials
    are 401 and non-allowlisted/disabled humans 403 in the auth layer, before the controller. `AdminMeController` reads the
    attached principal through `AdminPrincipalResolver`; it never re-authenticates or reads the token.
  - **Response** (`ApiDtos.AdminMeResponse`, camelCase): `actorType`, `actorId` (`google:<sub>` / `service:<role>`), `email`
    (HUMAN_ADMIN only: the allowlist's configured label; ABSENT for service accounts, never fabricated) and `roles` (from the
    principal, sorted). No credential id, separate subject, token, claims, issuer, audience, `hd` or timestamps.
  - **Email label:** `HumanAdminAllowlist` now retains each validated entry's email label, exposed only through
    `AdminProfiles.emailLabel(principal)` in `admin.auth` (provider + subject lookup). It is display metadata: not in
    `AdminPrincipal`, `Actor` or audit; never compared with the token's email; changing it changes only `/me`, never identity,
    authorization or audit history. `AdminAuthConfig` now publishes the validated `HumanAdminSettings` as a bean shared by the
    authenticator and the profile lookup (validation and startup failures unchanged).
  - **Pure read:** no Mongo, event or audit write (tested by collection counts). No new collection, index or migration;
    `SchemaBootstrap`, the committed public `docs/api/v1/openapi.yaml`, CORS, the verifier, authenticators and `ApiAuthFilter`
    are unchanged. The endpoint appears in the generated, token-protected internal `/v3/api-docs` (tested), and the committed
    internal export `services/catalog-service/docs/openapi.json` (regenerated by `OpenApiExportIT`) gains exactly the
    `GET /api/v1/admin/me` path and the `AdminMeResponse` schema.
  - **Next (not started):** `tazzzo-web` CMS scaffold + BFF auth (same-origin, authorization code + state + nonce + PKCE,
    opaque HttpOnly cookie, server-side session store, no browser token storage), pending framework/hosting ratification, the
    session store, the Google web OAuth client and the CMS hostname. Humans are not yet off the shared `cms-writer` token.
  - **Deployment gates unchanged:** six, all PENDING / UNVERIFIED.

- **Admin audit-read API** (`GET /api/v1/admin/audit-events`): **COMPLETE** (PR #49, squash `822728694cb5dd80a5b68c4587c6c79911222adc`, merged-`main` CI run `37138053909`, 2457 tests, `IndexContractIT` 10/10, `ModuleBoundaryTest` 73/73). Ready **in code**; the real CMS → real backend → persisted `HUMAN_ADMIN` audit round trip is NOT yet verified, and nothing here is production-ready.
  **A read-only, paginated, filterable view of the EXISTING persisted audit ledgers for per-person human admins holding the new
  narrow `audit-reader` role. Nothing is written; no second audit model; existing audit writes are unchanged.**
  - **Sources (no central audit collection exists, by design):** `product_events`, `node_events`, `domain_events`, attributed
    rows only (`actor` is a document). `price_events` is NOT a source: it is the retained, mixed-shape price ledger (not an actor-attributed audit ledger), and an attributed price
    change is already a `PRICE_UPDATED` product event. Unattributed historical rows are never returned.
  - **Permission:** `audit-reader` (new `HumanAdminSettings.KNOWN_ROLES` entry, allowlist only; no OIDC validation change).
    `AdminPrincipal.canReadAudit()` = HUMAN_ADMIN AND `audit-reader`. Missing/invalid credential 401; any other principal
    (reader, cms-writer, both shared service tokens) 403 `FORBIDDEN`, checked before any parameter is read. Service accounts are
    never granted audit-read. `ApiAuthFilter` no longer lets a principal with neither `reader` nor `cms-writer` (i.e.
    audit-reader alone) read the general INTERNAL GET surface: it may GET exactly `/api/v1/admin/me` and
    `/api/v1/admin/audit-events` (exact URI match). Existing readers/writers keep every read.
  - **Surface:** GET only. POST/PUT/PATCH/DELETE: 403 for a non-writer, 405 for a writer; nothing can mutate an audit row.
  - **Query:** newest first by (`at` DESC, source rank, `_id` DESC), a total order, so identical timestamps neither duplicate nor
    skip rows. Keyset (cursor) paging, default limit 50, range 1..100. The cursor is opaque base64url, bound to its filters
    (replay under other filters is 400), strictly decoded (malformed 400). New events (newer `at`) sort before the cursor and
    never shift later pages. Filters, all exact, ANDed: `actorType`, `actorId`, `action`, `targetType` (+`targetId`),
    `requestId` (`req_[0-9a-f]{20}`), `from`/`to` (UTC instants, inclusive, `from` <= `to`). Anything else (unknown, repeated or
    empty parameter, operator, regex, JSON, sort, projection) is 400 `MALFORMED_REQUEST` with a fixed message that never echoes
    the value. A corrupt persisted row is a generic 500.
  - **Raw query syntax (LOW-1 hardening):** the container silently drops a parameter it cannot decode (`actorType=%zz`) and an
    empty-named component (`=x`), which would have turned a filter into an unfiltered query and a bad `cursor` into a restart.
    `RawQuerySyntax` validates the RAW query string (after the 401/403 checks, before `AuditEventQuery.parse`) and answers 400
    `query string is malformed`: empty components (`&&`, `&a`, `a&`), empty names, any `%` without two ASCII hex digits, percent
    sequences that are not well-formed UTF-8, and any difference between the raw component count and the number of values the
    container bound. It is syntax only: it never decodes twice, repairs or normalises, and allowlisting stays with `AuditEventQuery`.
    Scoped to this endpoint; other surfaces are unchanged. Raw `[`/`]` and oversized queries are still refused earlier by the
    container with its own 400.
  - **Response** (`AuditEventsResponse{items, nextCursor}`, allowlisted `AuditEventDto`): `id` (`pe_|ne_|de_` + ObjectId),
    `occurredAt`, `action`, `targetType`, `targetId`, `actorType`, `actorId`, `credentialId`, `requestId`. The ledger `detail`
    map is never read. `credentialId` is the persisted non-secret label or null (never manufactured); `requestId` is null only
    for SYSTEM actors. No token, header, session, claim, email, stack.
  - **Indexes** (`SchemaBootstrap`, partial on `actor` being a document, no TTL), per ledger: `audit_read_recent` (at -1, _id -1),
    `audit_read_actor` (actor.id, at -1, _id -1), `audit_read_request` (actor.request_id, at -1, _id -1). `AuditReadIndexIT`
    explains the executed queries: IXSCAN, no COLLSCAN, no blocking SORT, keys/docs examined = rows returned (default page and
    cursor page on `audit_read_recent`, request-id lookup on `audit_read_request`, actor lookup on `audit_read_actor`).
  - **Retention:** none. The event ledgers have no TTL and nothing purges them (`price_events` too, since R1 was fixed). Retention policy is
    a deployment/compliance decision, not made here.
  - **Read auditing:** none exists in this backend and none is invented. Reads are counted by a bounded metric
    `admin_audit_read{outcome=served|forbidden|invalid}` and logged with request id and result size only (no actor id, filter,
    cursor or token).
  - **Deployment gates unchanged:** six, all PENDING / UNVERIFIED. New collection: none. Index change is additive via `SchemaBootstrap`.


## Follow-up debt (recorded)

- **Non-Auth `tx.run` result-holder audit (PR-11D, no action taken):** `AttributeAuthoringService`
  (`version[]`, two sites), `EvidenceService` (`outcome[]`), `RollupService.purge` (`deleted[]`, removed by R1) and
  `TaintService.processBatch` (`last[]`) share the same STRUCTURE (a one-element array written in the
  callback and read after `tx.run`) but are NOT retry bugs today: every non-throwing path of every
  attempt overwrites the holder, so the value read is always the last attempt's. They are structurally
  fragile; migrate to `Tx.call` opportunistically when next touched. `InventoryService`,
  `PricingService`, `MediaService` (`r[]`) and `TaxonomyChangeService` (`seq[]`) declare the array
  INSIDE the callback and use it within the same attempt — not holders. No non-Auth HIGH retry defect
  was found.
- **M2 — batch catalog eligibility** (`CatalogCardReadPort.findEligibleCards(Collection<String>)`) to
  replace up to 50 point reads per cart response. Deferred to avoid a catalog-read refactor in the cart PR.

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
- **Customer Address (PR-12B): COMPLETE.** Authenticated CRUD of saved delivery addresses plus
  dynamic serviceability binding, a NEW `com.tazzzo.customer.address` domain package — see "Merged
  on main" above.
- **Serviceability**: the existing PR-06/PR-10B foundation (`com.tazzzo.serviceability`,
  `PublicServiceability`, pincode-keyed routing) is **COMPLETE** and unchanged; the
  customer-address BINDING to it (PR-12B) is **COMPLETE**.
  (Address ↔ Serviceability binding: COMPLETE.)
- Cart (PR-12C): **COMPLETE** (PR #21). Auth transaction retry hardening (PR-11D): **COMPLETE** (PR #22). Checkout (PR-13A): **COMPLETE** (PR #23). Checkout provenance (PR-13B): **COMPLETE** (PR #24, squash `8b4fabb9b82e4e3502202ffaff1aadd35f9ba311`). Inventory Reservation lifecycle (PR-14A): **COMPLETE** (PR #25, squash `44438031022238334ecdf3995ba1096101e2e47f`). Order Foundation (PR-14B): **COMPLETE** (PR #26, squash `b620538e34afc35f7f080461750681b467ed4096`). Cart purchase-finalization seam (PR-15A-0): **COMPLETE** (PR #28, squash `611829c3649de3f5c37dec4ac5b375a1d8ef454e`). COD Order domain (PR-15A-1): **COMPLETE** (PR #29, squash `e8d4d45e88ad4935f7ad84a46ae65a671e50ba63`). Customer Order HTTP (PR-15A-2): **COMPLETE** (PR #30, squash `0cdcc97b8fcf5f7c079815b8cb276874635d15d6`; operational `orders`-count==0 deployment gate **PENDING**, not verified). Membership write foundation (PR-16A-1): **COMPLETE** (PR #31, squash `d32a23fb2e4b52fa8076de45a07bf3b60912b1e2`; Membership deployment gates **PENDING**, not verified). Membership entitlement read seam (PR-16A-2): **COMPLETE** (PR #32, squash `580633abbc8d162f45110443f503d4998f4caa48`). Membership termination (PR-16A-3): **COMPLETE** (PR #33, squash `99e2d1b7cc26982e0825bc4a9766b815aa6ae8e0`; cancel-at-period-end and immediate revoke; internal only). Benefits Foundation (order-level percentage + threshold evaluation seam; internal, no persistence): **COMPLETE** (PR #34, squash `cb2097f5979fe666d0d52de958b40db7058e3d16`; 0 production rules configured). Order Benefits snapshot (authoritative Benefits evaluation at COD placement; persisted snapshot, no public API change): **COMPLETE** (PR #35, squash `4486044e1c2f200ed05fe44abbb6525bf24a98f7`). Checkout Benefits evaluation snapshot (internal advisory preview persisted with the quote; no public API change): **COMPLETE** (PR #36, squash `30dea72482f71418048783b348647fdc0165a133`). Checkout Benefits preview public projection (additive nested `benefitPreview`, projection/API only): **COMPLETE** (PR #37, squash `48380964edb18f35eef501eb5aaf495da95d5c32`). Order money snapshot (authoritative V1 payable): **COMPLETE** (PR #38, squash `8051c45b7cf96dbc8d1374892d3b18281ff4a7b4`). Checkout advisory money snapshot + `moneyPreview`: **COMPLETE** (PR #39, squash `f98aafb67d946c04a7a7141f164e1d0998ab759b`). Benefits static config hardening: **COMPLETE** (PR #41, squash `22416e7a90aa36bc557d0926cc4b44dd7007a6b4`). PR-21 binding payable contract (PR #40, squash `3839f3d26e94f7d6cfed6e0da098ed900fd95a04`): merged without ratification; binding removed by the forward fix, public Order money kept. Advisory payable forward fix: **COMPLETE** (PR #42, squash `89fcf349c24d67dd610c28eafa34d2e70669195a`). Checkout/Order architecture boundary hardening: **COMPLETE** (PR #43, squash `0dd83b51d9fd74f17960c769648a227ed0ee89d0`). Admin principal + actor-attributed audit foundation: **COMPLETE** (PR #44, squash `2e8d87bd59bbb1d81c355b43b083694e6b928cc0`). Human Admin Google OIDC + config allowlist: **COMPLETE** (PR #45, squash `d018cac373c0461fb0d4c7eaa56a425a883f7b3f`). Admin `/me` CMS bootstrap identity: **IN REVIEW**. Order money snapshot (authoritative V1 payable: merchandise subtotal minus Benefits discount; internal, no public API change): **IN REVIEW**. Payment: **NOT STARTED**. Real payment gateway: **NOT STARTED**.

## Next (ratified sequence)

1. **Membership Foundation** — PR-16A-1 write foundation (complete), PR-16A-2 entitlement read seam
   (complete), PR-16A-3 termination (cancel-at-period-end, immediate revoke; complete, PR #33).
2. Benefits / Promotion engine (the only place discount percentages and thresholds will live) — first foundation
   slice complete (PR #34); flat/free-delivery/coupon/other benefit types and ratified launch rules remain undefined.
3. Checkout + Order money-model upgrade — first slice (Order Benefits snapshot: authoritative evaluation at COD
   placement, persisted snapshot) complete (PR #35); second slice (Checkout Benefits evaluation snapshot: internal
   advisory preview persisted with the quote) complete (PR #36); third slice (minimal public `benefitPreview`
   projection) complete (PR #37); fourth slice (Order money snapshot: authoritative V1 payable, internal) complete (PR #38); fifth slice
   (Checkout advisory money snapshot + public `moneyPreview`) complete (PR #39); sixth slice (Benefits static config
   hardening: plan cross-validation) complete (PR #41); PR #40 (binding payable, unratified) was merged and its binding is
   removed by the forward fix (complete, PR #42), which keeps the public authoritative Order money; the Checkout/Order
   architecture boundary hardening is complete (PR #43); the admin principal + actor-audit foundation is complete
   (PR #44); human admin Google OIDC + config allowlist is complete (PR #45); Admin `/me` is complete (PR #46); the admin audit-read API is
   complete (PR #49); then the `tazzzo-web` CMS BFF login; Payment comes last.
4. Payment domain, then the prepaid Order flow, then a real gateway.
5. Admin/CMS expansion.

Search, Notifications, app integration and AWS infrastructure remain unscoped.

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

- **2026-10-05** — W (`feature/e2e-journey-payment-readiness`): `CustomerJourneyE2EIT` drives the whole customer journey over real HTTP/Mongo/Redis (browse → PIN → OTP request/verify → session → profile → address → cart → quote → COD order → read → refresh rotation → logout → token refused). `docs/payments/PAYMENT_READINESS_REPORT.md`: COD works end to end; online payment not implemented (provider and policy decisions outstanding, all gates UNVERIFIED).

- **2026-10-05** — Platform hardening PR-U (`feature/platform-hardening`): security headers on every response (incl. filter-written refusals), static log-safety guard over every log argument, LOGGING OTP provider refused outside unset/local/test/dev, dependency-free secret scan as a CI job, Dependabot for Maven + Actions. See `docs/ops/HARDENING.md`.

- **2026-10-05** — Container image + deployment contract PR-V (`feature/container-image-deploy`): digest-pinned non-root layered image with no baked configuration, `.dockerignore` allowlist, graceful shutdown (`TAZZZO_SHUTDOWN_GRACE`, 25 s), CI image build (no push), `docs/ops/DEPLOYMENT.md` (roles, env, container contract, rollout, UNVERIFIED gates). Proven locally against a throwaway Mongo: migration job exit 0, runtime start on a read-only rootfs, fail-closed refusals, graceful SIGTERM.

- **2026-10-05** — Cart age policy (`feature/cart-age-policy`): by time since the last mutation, <24 h FRESH, 24 h–7 d inclusive REVALIDATE (kept; GET returns `freshness: REVALIDATE` and lines carry `PRICE_CHANGED` when the current price differs from the price observed when the line was set — informational, never blocks checkout), >7 d expired (the boundary moved from "expired at exactly 7 d" to "kept at exactly 7 d"; housekeeping CAS now `expiresAt < now`). A REVALIDATE read never writes. Lines record `unitPricePaiseAtUpdate` (best-effort observation, never a price authority).

- **2026-10-05** — Address create idempotency (`feature/address-create-idempotency`, closure item 5, app request `BACKEND_INTEGRATION_READINESS.md:522-526`): optional `Idempotency-Key` on `POST /v1/customer/addresses` (checkout's grammar). Same customer+key+normalised body → 201 with the same address (current state), never a second address or a second count against the limit; different body or a since-deleted address → 409 `IDEMPOTENCY_CONFLICT`; keys per customer; key row written in the address's own transaction (a concurrent same-key race resolves to the winner); only a SHA-256 digest of the key is stored; rows expire (`customer_address_idempotency`, V0015 TTL + erasure lookup). Without the header, behaviour is unchanged.
- **2026-10-05** — Closure: notification hooks + account-erasure wiring + dashboard (`feature/closure-notify-erasure`, stacked on #68 (→#64→#63→#58, #66), #67, #56, #71): ORDER_OUT_FOR_DELIVERY / ORDER_DELIVERED / ORDER_CANCELLED (staff and customer) and SUPPORT_REPLY / SUPPORT_CASE_RESOLVED enqueued inside their business transactions; account deletion now erases support cases and notification-outbox rows in its transaction (`SupportErasure`, `NotificationErasure`); bounded `GET /api/v1/admin/dashboard/summary`. Docs: `docs/ops/NOTIFICATIONS.md`, `docs/ops/ADMIN_DASHBOARD.md`.

- **2026-10-05** — Notification outbox N2 (`feature/notification-outbox`): `notification_outbox` (+V0014: due scan, erasure lookup, TTL); COD placement enqueues `ORDER_CONFIRMED` in its own transaction; leased, token-conditional dispatcher with backoff, max attempts and max age; provider port with a disabled default (enabling dispatch without a provider is a startup failure). See `docs/ops/NOTIFICATIONS.md`. Provider UNVERIFIED (external decision).

- **2026-10-05** — CMS help centre + legal links (`feature/cms-faq-legal`, stacked on #70): typed FAQ blocks (placement `HELP`, type `FAQ`, closed category DELIVERY/PRODUCT/CLUB/PAYMENT/REFUND/ACCOUNT, plain-text question ≤200 / answer ≤2000, no markup) on the existing content lifecycle (DRAFT→PUBLISHED→ARCHIVED, window, CAS + audit), each type bound to exactly one placement; public `GET /v1/content/faqs` (optional `category`, admission-charged on its own route, `public, max-age=60`); app config gains https-only `termsUrl`/`privacyUrl`/`refundPolicyUrl`, exposed as `legal` on `GET /v1/app-config`. No new collection or index (reuses `content_by_placement_status_sort`). The app's docs name `/support/v1/faqs`; the served path is `/v1/content/faqs`.

- **2026-10-05** — CMS home content + app operational config (backend completion PR-Q; base `main` `484d42c`): admin `POST/GET/PUT /api/v1/admin/content/blocks[/{id}]` + `POST /{id}/status` (DRAFT → PUBLISHED ↔ DRAFT → ARCHIVED, final; CAS;
  audit with the authenticated actor in the SAME transaction) for BANNER (safe asset key + a link from a closed grammar: product/category/search — never an arbitrary URL), PRODUCT_RAIL (1..20 product ids) and CATEGORY_GRID (1..12 node ids),
  with optional time windows; public `GET /v1/content/home` (PUBLISHED blocks inside their window by the server clock, display order, banner image URLs resolved through the media CDN base — a banner is dropped, never shown broken, when no base is
  configured; `public, max-age=60`; admission-charged on its own bounded route). App config: admin `GET/PUT /api/v1/admin/app-config` (CAS; create with version 0) and public `GET /v1/app-config` (store open, maintenance message, min/latest
  Android & iOS versions, support contacts). New collection `content_blocks` (roster, role file, docs — 50 collections) with migration `V0013`; the app config is the `system_config` document `app_config` (no new collection).
- **2026-10-05** — Customer-surface rate limits (`feature/customer-rate-limits`): per-customer read/write buckets on `/v1/customer/**` after authentication, 429 + Retry-After, fail-closed 503 on store outage, bounded metric; off unless configured, startup failure on partial config or a missing store. See `docs/ops/CUSTOMER_RATE_LIMITS.md`. Values UNVERIFIED (deployment decision).

- **2026-10-05** — Customer account deletion / erasure (backend completion PR-C; base `main` `484d42c`): `POST /v1/customer/account/deletion` with explicit `{"confirm":"DELETE"}`,
  orchestrated by the new top-level `com.tazzzo.account` slice in ONE transaction: sessions revoked (tokens dead at the next request), profile/addresses/address state/cart/quotes deleted, order address
  snapshots anonymised (orders retained as commercial records), entitling membership term revoked in-session, customer row tombstoned (phone replaced by a per-id placeholder, so the phone can register
  again as a NEW customer), the phone's OTP rows purged, and a `CUSTOMER_ACCOUNT_DELETED` audit event with counts only. Each module erases its own data (`*Erasure` components, reachable only from the
  orchestrator — pinned by ModuleBoundaryTest). Idempotent and concurrency-safe (conditional tombstone). No retention PERIOD is invented. PII map updated (`DATABASE_RETENTION_AND_PII.md`).
- **2026-10-05** — OTP delivery gateway adapter (backend completion PR-N1; base `main` `484d42c`): `tazzzo.customer-auth.otp.provider-mode=HTTP` wires `HttpOtpDeliveryProvider`, a vendor-neutral HTTPS adapter behind the existing
  `OtpDeliveryProvider` port (POST JSON `{to,message,sender}` with a credential header; any 2xx = accepted). Validated fail-closed at STARTUP (https only except loopback, credential present and never printed, template carries `{otp}`, timeouts
  fit inside `delivery-timeout-seconds`); redirects never followed; bounded timeouts; the code, phone, URL, credential and response body appear in no log/exception/metric; one bounded metric `otp_gateway_send{outcome}`. A gateway failure is 503
  with no challenge activated (a prior working code is untouched). `docs/ops/OTP_GATEWAY.md` documents the contract. **External gate (UNVERIFIED):** no real SMS vendor was contacted — vendor choice, India DLT template registration, delivery
  receipts, failover vendor and spend caps remain open. Not built here: the notification outbox for order events (a separate PR).
- **2026-10-05** — Staff order operations + fulfilment statuses (backend completion PR-M2; STACKED on PR-M #64 with PR-P #66 merged in): `GET /api/v1/admin/orders[?status]` (newest first, keyset; V0012 indexes), `GET /{id}` (with the delivery
  address — fulfilment needs it), `POST /{id}/transition` in the orders namespace (order-ops writes, support-agent reads; catalogue roles/shared tokens never reach it). State machine: CONFIRMED(v2) → OUT_FOR_DELIVERY(v3) → DELIVERED(v4); CANCELLED
  from CONFIRMED (v3) or OUT_FOR_DELIVERY (v4, a failed/refused delivery) with a closed staff reason set; every transition is a CAS on (status, version) with the audit row (authenticated actor) in the SAME transaction; a staff cancel returns the
  stock (exactly-once) and releases the slot hold. A DELIVERED order is never cancelled (returns are not modelled); a customer can no longer cancel once the order is out for delivery (409). Customers see the new statuses with
  `outForDeliveryAt`/`deliveredAt`. Cash collection on delivery is a payment concern and is NOT recorded.
- **2026-10-05** — Support cases (backend completion PR-O; STACKED on PR-P #66): customer `POST/GET /v1/customer/support/cases`, `GET /{id}`, `POST /{id}/messages`, `POST /{id}/close` (owner is always the verified principal; an
  `orderId` must be one of the caller's own orders; at most 5 open cases; closed field set — no identifier, status or role is accepted from the body) and staff `GET /api/v1/admin/support/cases[?status]`, `GET /{id}`, `POST /{id}/messages`,
  `/assign` (to self, CAS) and `/status` (IN_PROGRESS/RESOLVED/CLOSED, CAS) in the staff namespace (support-agent read+write, order-ops read-only). Threads are bounded (100 messages, 2000 chars, plain text) and every reply is an
  atomic guarded append; staff actions write their audit row in the SAME transaction as the change. Collection `support_cases` (roster, role file, docs, 50 collections), migration `V0011` (3 indexes). Case text is personal data:
  `SupportService.eraseForCustomer` deletes a customer's cases — **wiring it into the account-deletion orchestrator (PR-C #56) is a merge-time follow-up** since the two branches are independent. Retention period: TBD — production policy.
- **2026-10-05** — Staff roles and the access policy (backend completion PR-P; base `main` `484d42c`): new roles `order-ops` and `support-agent` (human admins only, via the allowlist) and `AdminAccessPolicy`, now the ONE place that
  authorises an INTERNAL request (`ApiAuthFilter` delegates; legacy rules for unchanged paths are identical). Two staff namespaces are reserved for the order-operations and support APIs that follow: `/api/v1/admin/orders/**`
  (order-ops read+write, support-agent read) and `/api/v1/admin/support/**` (support-agent read+write, order-ops read); cms-writer, reader, audit-reader and the shared service tokens do NOT reach them, and a staff role confers nothing on the
  catalogue surface (only `/me`). Exact segment-boundary matching; a staff role on a service account is refused. `docs/ops/ADMIN_ROLES.md` is the matrix. **Open (owner decision):** splitting pricing/stock/delivery writes out of the
  broad `cms-writer` role — it would change the shared service token's existing reach.
- **2026-10-05** — Customer order history and cancellation (backend completion PR-M; STACKED on PR-K/L #63 → PR-E #58): `GET /v1/customer/orders` (newest first, keyset by `(createdAt, _id)`, CONFIRMED + CANCELLED only, summaries
  without address/lines, index `order_by_customer_recent` via migration `V0010`) and `POST /v1/customer/orders/{id}/cancel` (`{"reason": CHANGED_MIND|ORDERED_BY_MISTAKE|OTHER}`). Cancel is ONE transaction: CAS `CONFIRMED(v2)→CANCELLED(v3)` with who/when/why,
  then — only if that call won the CAS — release the delivery slot hold and return the consumed stock through the new exactly-once `InventoryReservationPort.restockConsumed` (one-shot `restockedAt` marker on the reservation header; its status stays
  CONSUMED), plus an `ORDER_CANCELLED` audit event; any failure rolls back the lot, a repeat cancel is an idempotent 200, concurrent cancels restock exactly once. Customer cancellation is bounded by
  `tazzzo.orders.customer-cancel-window-seconds` — the DEFAULT 0 DISABLES it (409 `CANCELLATION_WINDOW_CLOSED`) until the business sets a window (a policy decision, not invented here). A re-sent placement of a cancelled quote returns the
  cancelled order and never re-orders. **Not built (needs the staff-role model, PR-P):** admin order list/read/transition and the fulfilment statuses (out for delivery, delivered); cancel-by-staff.
- **2026-10-05** — Delivery slot at order placement (backend completion PR-K/L; STACKED on PR-E #58 — retarget to `main` after it merges): `POST /v1/customer/orders` accepts an optional `deliverySlotId`
  (`<window>~<yyyy-MM-dd>`, from `GET /v1/customer/delivery/slots`). It is reserved in the SAME transaction as the COD placement through `DeliverySlotService.reserveForOrder` (PIN → service area → atomic hold keyed by the order id): a full,
  closed, unknown or out-of-horizon slot is `409 DELIVERY_SLOT_UNAVAILABLE` and the whole placement rolls back (stock, cart marker, order, hold); a malformed id is 400; a replay returns the original order and never re-reserves. The
  order stores an `OrderDeliverySlot` snapshot (internal area/window/date for the later release on cancellation; the customer sees only `slotId`, `label`, `startsAt`, `endsAt`). `tazzzo.checkout.delivery-slot-required` (default false)
  makes a slot mandatory. Orders placed without a slot are unchanged. The cart and checkout-quote contracts are untouched (no quote fingerprint change). Releasing the hold on cancellation is PR-M.
- **2026-10-05** — Consumer product search (backend completion PR-G; base `main` `484d42c`): `GET /v1/search?q=&pin=&page_size=&cursor=` on the PUBLIC commerce surface. Query → lower-cased tokens (shared
  `SearchTokens` normalisation, ≤64 chars, ≤5 tokens, ≥2 chars each); candidates from `product_card_base.search_tokens` (anchored-prefix `$all`, served by the new multikey index `card_search_tokens`, migration `V0009`)
  restricted to the release's reachable verticals; every candidate is then re-checked against `products` with `ConsumerEligibility.within` (a stale projection row can hide a product, never show an ineligible one);
  the page is enriched through the same composer/enricher as the category list (price, stock, serviceability), keyset-paged by sku with the same signed commerce cursor (query bound as a keyed fingerprint;
  location bound as before). Admission charged `1 + page_size` on the new bounded route `commerce_search`. The projector writes tokens and backfills older rows on their next rebuild (explicit exception to the
  content-NOOP rule). **Not built (product decision):** relevance ranking / synonyms / typo tolerance — results are deterministic sku order; a ranking choice (Atlas Search vs in-house scoring) needs a product ruling.
- **2026-10-05** — Delivery slots (backend completion PR-E; base `main` `484d42c`): new `com.tazzzo.delivery` slice. Admin API `GET/PUT /api/v1/admin/delivery-slots/{serviceAreaId}[/{windowId}]` +
  `POST …/activate|deactivate` (recurring windows per service area: local start/end minute, cutoff, capacity, ISO weekdays; CAS `expectedVersion`; audit-before-state with the AUTHENTICATED actor; area must exist), customer
  `GET /v1/customer/delivery/slots?pin=&days=` (fresh availability in the configured zone `tazzzo.delivery.zone`, default `Asia/Kolkata`, horizon `tazzzo.delivery.horizon-days`, default 3; status only, never counts), and the
  atomic hold primitives `reserve`/`release` that checkout/order placement (PR-L/M) will call inside THEIR transaction: idempotent per hold id, `used < capacity` conditional increment is the sole "full" decision (24-way
  concurrency test: exactly capacity holds). Two collections added to the roster (`delivery_slot_windows`, `delivery_slot_usage`), migration `V0008` (by-area index + the only durable-collection TTL: counters purged a week
  after the slot date), runtime role regenerated, DB docs/pins updated (51 collections, 62 indexes). Not wired into checkout yet (PR-L).
- **2026-10-05** — R1 price-history retention (backend completion PR #1; base `main` `4b27f32`): `./mvnw clean test` on Java 21 + Docker: **BUILD SUCCESS**, 2864 tests (1277 unit, 1587 integration), 0 failures / 0 errors / 0 skipped (+14 over 2850:
  `PriceHistoryRetentionIT` 13, `PriceHistoryRetentionSourceTest` 3, minus the 2 `RollupStallIT` tests that pinned the purge); `ModuleBoundaryTest` 73/73, `IndexContractIT` 12/12. Evidence (real MongoDB 7, real writers): a paise ledger written by
  `PricingService` survives the roll-up byte-identical and is never aggregated; legacy offer events are all retained and flagged once; second run, new event, out-of-order older event, restart and four overlapping runs never double-count; a failed projection
  leaves the whole history unflagged and intact and the retry succeeds; `price_current`, customer price reads and version CAS are unchanged; no TTL on `price_events`. Mutations, each killed: R1-M1 purge restored, M2 claim step consumes the event,
  M3 already-processed filtering removed, M4a/b shape filter removed (paise row reaches the aggregate, null aggregate), M5 destructive cleanup after a projection failure. No migration; V0001–V0007 checksums unchanged. R1 is fixed in code and still to be
  confirmed on a real staging database. Carried LOW: `PricingService.upsertPrice(cmd)` (unattributed overload) still exists, unused outside tests; the roll-up select scans non-legacy ledger rows' index entries each run (a typed partial index would remove that; it needs a migration and is deliberately not done here).
- **2026-10-05** — Platform HTTP baseline (backend completion PR-B; base `main` `484d42c`): health probes (`GET /health/live`, `GET /health/ready`, new exact `HEALTH` surface, unauthenticated,
  no-store, bounded words only; readiness = datastore gate OPEN + bounded Mongo ping, limiter store reported/optional), application-level request-body limit (64 KiB default, 413 before auth, chunked bodies
  bounded by buffering), sanitized framework errors (unreadable body, type mismatch, missing/unsatisfied parameter → 400, never the 500 catch-all — this closes the `GET /api/v1/products` without `canonicalKey`
  500 at the framework level), validated `X-Correlation-Id`, explicit CORS allowlist (off by default, exact https origins, no credentials), `forward-headers-strategy=none` + graceful shutdown. Filter chain pinned:
  request-id → body-limit → CORS → service-token auth → customer auth. Doc: `docs/ops/HTTP_PLATFORM_BASELINE.md`. Test evidence and mutations are recorded in the PR.
- **2026-10-05** — Catalogue/taxonomy admin completion (backend completion PR-F; base `main` `484d42c`): `GET /api/v1/products` was a **500** (the only mapping was `params="canonicalKey"`, and the unsatisfied-parameter
  exception fell through to the catch-all). It is now a real admin list — ascending-id keyset paging (`cursor` = last id), filters `verticalId` / `lifecycle` / `status` (the latter two require a vertical so no query is a collection scan on a
  secondary filter), closed parameter grammar (unknown/repeated/empty/oversized = 400) — and `ServletRequestBindingException` is now a 400 `MALFORMED_REQUEST`, never a 500. Taxonomy: `POST /api/v1/taxonomy/nodes` creates a
  super_category / category / sub_category / vertical under an ACTIVE parent of the right level inside the OPEN release (so consumers see it only when that release is published), duplicate active sibling = `DUPLICATE_NODE`,
  verticals must name an existing attribute schema, ids minted from per-prefix sequences (`TZS`/`TZC`/`TZG`/`TZV`, base 100000), audit event `created` with the authenticated actor; `GET /api/v1/taxonomy/nodes` lists nodes
  (filters `parentId`/`nodeType`/`status`, keyset paged). **Open product decision (not built):** sibling *reorder* — the consumer taxonomy order is the ratified transport order (name, then node id) that cursors and ETags hash, so a
  display order is a consumer-contract change (snapshot field + ordering + ETag), not an admin-only feature.
- **2026-10-05** — Bulk product catalogue import (`feature/bulk-product-import`, stacked on #73): `POST /api/v1/admin/imports/products`, 1–500 single-create-shaped rows, whole-file validation reusing governance, canonical-key derivation and the products `$jsonSchema` validator (aborted-transaction probe) plus release/vertical existence and GS1 check digits; in-file and existing-owner identity conflicts; identical existing product = UNCHANGED (re-submit safe); apply through `MintService.mint`; a 500-row file loads in one call. `docs/ops/BULK_IMPORT.md` gains the end-to-end 500-SKU load procedure. **No 500-SKU dataset exists in any repo; none is loaded.**

- **2026-10-05** — Bulk price/stock import PR-T (`feature/bulk-price-stock-import`, stacked on #61): `POST /api/v1/admin/imports/{prices,inventory}`, 1–500 rows, whole-file validation (422 with every row error, nothing written), dry run, per-row attributed CAS writes through the single-row service path, stop-on-datastore-failure with NOT_ATTEMPTED rows, one summary audit row per run. See `docs/ops/BULK_IMPORT.md`.

- **2026-10-05** — Price and stock admin APIs (backend completion PR-H/PR-I; base `main` `484d42c`): INTERNAL `GET/PUT /api/v1/admin/prices/{sku}` (explicit paise, `expectedVersion` absent = create / present = CAS, sanity ceiling and
  MRP >= selling enforced by `PricingService`, no effective windows offered) and `GET/PUT /api/v1/admin/inventory/{sku}/{location}` + `POST …/activate|deactivate` (ABSOLUTE stock set, CAS, can never push `onHand` below live
  reservations, never touches `reserved`; delist keeps the counters). Both require an existing product, record the AUTHENTICATED actor (ledger row / product event; a body field is ignored) and are read-only for read credentials.
  The unattributed `PricingService.upsertPrice(cmd)` and `InventoryService.setInventory(cmd)` overloads are now fixture seams that NO production class may call (two ArchUnit rules); `setInventory(cmd, actor)` and
  `setActive(actor, …)` are new. `InventoryKey` now bounds ids like the serviceability route does (max 128, trimmed, no control chars) — previously any string was accepted. Open: fulfilment locations are still only the ids
  inside service-area routes (no location master); the admin API does not verify a location exists.
- **2026-10-05** — Media admin + storage abstraction (backend completion PR-J; base `main` `484d42c`): INTERNAL `POST /api/v1/admin/media/uploads` (server-generated key `p/<owner type>/<owner id>/<uuid>.<ext>`, content-type allowlist
  jpeg/png/webp, size ceiling `tazzzo.media.max-upload-bytes` default 5 MiB, owner must be an existing product; returns a short-lived direct-to-storage target — the platform never proxies bytes), `GET/PUT /api/v1/admin/media/{product|sku}/{id}`
  (whole-set replace, CAS; one PRIMARY at order 0, unique ids/orders, unsafe keys refused by the domain). With storage configured, every NEWLY referenced key is verified against what storage really holds (exists, within the ceiling,
  magic bytes = declared type; svg/html/gif refused) — closing the documented gap that `contentType` was only caller-asserted. New port `MediaStorage` (default `DisabledMediaStorage`: uploads are `503 MEDIA_STORAGE_NOT_CONFIGURED`, set
  writes stay metadata-only/unverified). Writes are attributed (`upsertMediaSet(cmd, actor)`; the actor-less overload is closed to production code by an ArchUnit rule). **External gate (UNVERIFIED):** no S3/GCS provider ships — choosing one
  needs a decision + credential in the secret store; CDN base (`tazzzo.media.public-base-url`) and image processing/resizing remain unprovisioned. Not built: media-set activate/deactivate, delete of orphaned objects.
- **2026-10-05** — Location + serviceability admin (backend completion PR-D; base `main` `484d42c`): INTERNAL admin API `GET/PUT /api/v1/admin/service-areas[/{pin}]` and `POST /{pin}/activate|deactivate`
  (CAS on `expectedVersion`, create-vs-update explicit, route invariants in the domain, audit-before-state with the AUTHENTICATED actor — never the body; read-only credentials can read, not write), living in the
  serviceability slice (`ServiceAreaAdminController`). New `com.tazzzo.location` geo port (`GeoPincodeResolver`, default `DisabledGeoPincodeResolver`): when an operator configures a provider, `lat`+`lng` on
  `GET /v1/serviceability` resolve to a PIN (provider called only AFTER admission is charged; provider text/coordinates never echoed or logged; out-of-PIN point = not serviceable; outage = 503). List/PDP stay PIN-only.
  No external provider ships: choosing one needs a business decision and a credential (`docs/ops/GEO_PROVIDER.md`), so that gate stays UNVERIFIED/external.
- **2026-10-04** — DB-4 final proxy-detector fix (narrow re-review of `4e19e5e`: the MongoDB driver accepts `;` as well as `&` between URI options, and the raw text scan only split on `&`, so `?w=majority;proxyHost=evil.example.net` kept a loopback target "local" and bypassed `PROXY_FORBIDDEN`):
  proxy use now comes from the driver's own parse (effective `ProxySettings`), not a second text parser; the dead raw-scan helper was removed. Mutations, each killed: DB4-P1 detector sees only `&`, P2 `isLocalTarget` ignores proxies, P3 contract does not reject the proxy,
  P4 `MigrationTarget` loses the proxy decision, P5 detector fails open on an unparseable string, P6 detector blind. `./mvnw clean test` on Java 21 + Docker: **BUILD SUCCESS**, 2850 tests (1274 unit, 1576 integration), 0 failures / 0 errors / 0 skipped (+7); `ModuleBoundaryTest` 73/73, `IndexContractIT` 12/12. V0001 checksum unchanged (`3b703e4a…`); R1 unchanged and open.
- **2026-10-04** — DB-4 final classifier / gate hardening (focused re-review of `010d0b9`: M2 octal-looking `0177.0.0.1` and proxy options kept a loopback target "local"; two gate states had no deterministic test):
  `./mvnw clean test` on Java 21 + Docker: **BUILD SUCCESS**, 2843 tests (1267 unit, 1576 integration), 0 failures / 0 errors / 0 skipped (+47 over 2796); `ModuleBoundaryTest` 73/73, `IndexContractIT` 12/12. One earlier full run
  had 22 integration classes error with `MongoSocketOpenException` to the shared Testcontainers server (an environment failure, no assertion failed); the rerun is clean. One strict classifier (`ConnectionContract.isLocalTarget`) now drives the verifier and `TargetGuard`
  (`MigrationTarget.local`): only `localhost`, a standard dotted-quad in `127/8`, a decimal integer in that range (confirmed against the JDK), `::1` and IPv4-mapped loopback, with no proxy option, are local; octal/hex/short spellings, `0.0.0.0`, proxies, SRV and mixed lists are enforced.
  Mutations, each killed: DB4-F1/F2 `0177`/`00177` local, F1b octal parser, F3 proxyHost ignored, F4 other proxy options ignored (equivalent at the URI level because the driver rejects them without `proxyHost`; pinned by a direct detector test), F5a/b mixed hosts local, F6 APPLY opens the gate, F7a/b runner refusal opens the gate,
  F8 `0.0.0.0` local, F9 runner ignores the URI, F10 TargetGuard ignores the decision, F11 SRV local, F12 verifier ignores the decision. V0001 checksum unchanged (`3b703e4a…`); R1 unchanged and open.
- **2026-10-04** — DB-4 focused hardening (PR #52 review: M1 scheduled workers ran before the verifier, M2 environment label trusted, M3 V0001 checksum coupled to live constants):
  `./mvnw clean test` on Java 21 + Docker: **BUILD SUCCESS**, 2796 tests (1221 unit, 1575 integration), 0 failures / 0 errors / 0 skipped (+123 over the reviewed 2673);
  `ModuleBoundaryTest` 73/73, `IndexContractIT` 12/12. Mutations, each killed: DB4-H1 worker ignores the gate, H2a/b/c/d gate opened early or job mode unmarked, H3/H4/H5 remote target labelled dev/test/local
  bypasses, H5b TargetGuard trusts the label, H6a/b V0001 reads the live collections, H7 V0001 reads the live index catalog (H6a is killed only by the source scan: live and frozen are equal today), H8a/b `URI_INVALID` echoes the
  URI or driver message; contract C1 journal, C2 wtimeout, C3 OCSP, C4 proxy, C5 blank replicaSet, L1–L5 loopback normalisation; prior DB-M1..M8 and DB4-M1..M10 rerun (DB-M7c, which had silently stopped being killed, is now pinned by `MigrationDefaultsTest`).
  V0001's stored checksum is unchanged (`3b703e4a…`). Not fixed here and still open: R1 (price purge), customer erasure, Atlas capability verification. Nothing was run against Atlas, AWS or production.
- **2026-10-04** — `./mvnw clean test` on Java 21 + Docker on `feature/db4-datastore-contract-and-privileges` (based on `main`
  `d9c440f3c1f4cddeb6c3578edbaee4c4d098c7c8`, whose push CI run `37145966422` had 2570 tests): **BUILD SUCCESS**, 2673 tests,
  0 failures / 0 errors / 0 skipped (+103: 83 unit, 20 integration — 14 `DatastorePrivilegeIT` + 4 `DatastoreWiringIT` + 2
  `RuntimeIdentityEndToEndIT` against a real authenticated MongoDB 7 replica set); `ModuleBoundaryTest` 73/73, `IndexContractIT` 12/12.
  Mutations, each killed: DB-M1 lock removed, DB-M2 duplicate migration id allowed, DB-M3 dry run writes, DB-M4 unique preflight removed, DB-M5 audit
  index key order, DB-M6 TTL on an audit ledger, DB-M7a/b/c runtime auto-migration (default mode, production guard, yml default), DB-M8 history not written;
  DB4-M1 verifier no longer first, M2 any write concern, M3 no TLS requirement, M4 excess privileges ignored, M5 standalone accepted, M6 runtime writes the
  history, M7 production not enforced, M8 driver message leaked, M9 VERIFY checked as the migrator, M10 database-wide runtime read. Nothing was run against Atlas, AWS or production.
- **2026-10-03** — merged-`main` verification of PR #49 (squash `822728694cb5dd80a5b68c4587c6c79911222adc`, push CI run `37138053909` on Java 21):
  **BUILD SUCCESS**, 2457 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 73/73, `IndexContractIT` 10/10.
- **2026-10-03** — `./mvnw clean test` on Java 21 + Docker on `feature/pr26-admin-me` (based on `main`
  `d018cac373c0461fb0d4c7eaa56a425a883f7b3f`): **BUILD SUCCESS**, 2382 tests, 0 failures / 0 errors / 0 skipped (2366 + 12
  `AdminMeIT` + 4 `AdminProfilesTest`); `ModuleBoundaryTest` 73/73. Mutations, each killed: `credentialId` exposed, raw subject
  exposed, token email used instead of the configured label, an email fabricated for service accounts, reader access removed,
  `/me` unauthenticated, email-based actor id, token roles instead of principal roles.
- **2026-10-03** — merged-`main` verification of PR #45 (squash `d018cac373c0461fb0d4c7eaa56a425a883f7b3f`, push CI run
  `37076192370` on Java 21): **BUILD SUCCESS**, 2366 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 73/73.
- **2026-10-03** — PR #45 review hardening (LOW-1 strict single audience + `azp`, NOTE-1 boolean-only `email_verified`):
  `./mvnw clean test` on Java 21 + Docker: **BUILD SUCCESS**, 2366 tests, 0 failures / 0 errors / 0 skipped (2357 + 9 net in
  `GoogleOidcAuthenticatorTest`); `ModuleBoundaryTest` 73/73. Mutations, each killed: audience count back to membership,
  `azp` equality skipped, configured + foreign audience allowed when `azp` is the admin client, string `"true"` accepted for
  `email_verified`.
- **2026-10-03** — `./mvnw clean test` on Java 21 + Docker on `feature/pr25-human-admin-oidc` (based on `main`
  `2e8d87bd59bbb1d81c355b43b083694e6b928cc0`): **BUILD SUCCESS**, 2357 tests, 0 failures / 0 errors / 0 skipped (2283 baseline +
  32 `GoogleOidcAuthenticatorTest` + 18 `AdminAuthConfigStartupTest` + 4 `ServiceTokenAuthenticatorTest` + 6 human cases in
  `ApiAuthFilterPrincipalTest` + 11 `HumanAdminOidcIT` + 3 ArchUnit rules); `ModuleBoundaryTest` 73/73. No live Google network
  call (local RSA keys, loopback JWKS server). Mutation checks, each killed: signature verification skipped, audience skipped,
  issuer skipped, expiry skipped, `alg=none` accepted, RS512/HS256 accepted, `hd` skipped, `email_verified` skipped, email as
  actor id, roles trusted from token claims, non-allowlisted human allowed, disabled admin allowed, raw ID token in the credential
  id, HUMAN_ADMIN produced as SERVICE_ACCOUNT, human audit request id differing from `X-Request-Id`, cms service token broken, a
  JWT-shaped token accepted on the customer surface, an unrecognised (customer) credential accepted on `/api/**` (with OIDC
  disabled and enabled), duplicate-subject validation removed, unknown role allowed, email stored in the audit actor. The two
  new confinement rules were also shown to fail on a scratch violation.
- **2026-10-03** — merged-`main` verification of PR #44 (squash `2e8d87bd59bbb1d81c355b43b083694e6b928cc0`, push CI run
  `37060802238` on Java 21): **BUILD SUCCESS**, 2283 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 70/70.
- **2026-10-03** — `./mvnw clean test` on Java 21 + Docker on `feature/pr24-admin-actor-audit-foundation` (based on `main`
  `0dd83b51d9fd74f17960c769648a227ed0ee89d0`): **BUILD SUCCESS**, 2283 tests, 0 failures / 0 errors / 0 skipped (2250 baseline +
  5 `ActorTest` + 4 `AdminPrincipalTest` + 6 `ApiAuthFilterPrincipalTest` + 9 `AdminActorAuditIT` + 3 ArchUnit rules, then +6
  `AdminActorFailClosedIT` in the fail-closed hardening; 243 existing
  test call sites now pass `TestActors.TEST`, a `system:test` actor); `ModuleBoundaryTest` 70/70. Twelve mutation checks were each
  killed: principal not attached, cms token mapped to HUMAN_ADMIN, actor request id differing from `X-Request-Id`, product event
  without actor, node event without actor, a service dropping the actor while still writing state, legacy actor-less events
  rejected, a malformed actor silently accepted, reader writes allowed, no metric on an invalid token, the raw token copied into
  the credential id, and `common.audit` depending on `admin.auth`. The hardening adds five more, each killed: the null guard
  removed from `ProductUpdateService.updateTitle`, `TaxonomyChangeService.renameNode`, `AttributeAuthoringService.createDefinition`
  and `EvidenceService.create`, and the PATCH controller passing a `null` actor.
- **2026-10-03** — merged-`main` verification of PR #43 (squash `0dd83b51d9fd74f17960c769648a227ed0ee89d0`, push CI run
  `37045625143` on Java 21): **BUILD SUCCESS**, 2250 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 67/67.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr23-checkout-order-arch-hardening` (based on `main`
  `89fcf349c24d67dd610c28eafa34d2e70669195a`): **BUILD SUCCESS**, 2250 tests, 0 failures / 0 errors / 0 skipped;
  `ModuleBoundaryTest` 67/67 (rules hardened, none added or removed; no production file changed).
- **2026-10-02** — merged-`main` verification of PR #42 (squash `89fcf349c24d67dd610c28eafa34d2e70669195a`, push CI run
  `37039217513` on Java 21): **BUILD SUCCESS**, 2250 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 67/67.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr22a-restore-advisory-payable` (based on `main`
  `3839f3d26e94f7d6cfed6e0da098ed900fd95a04`, i.e. after PR #40): **BUILD SUCCESS**, 2250 tests, 0 failures / 0 errors / 0
  skipped; `ModuleBoundaryTest` 67/67. The count is 9 below `main`'s 2259 because PR #40's binding-only tests were removed
  deliberately: `OrderBenefitsPlacementIT` 38 -> 33 (13 PR #40 tests out, 8 advisory/authoritative tests in), `OrderContractTest`
  9 -> 5 (6 binding/source-scan tests out, 2 positive contract pins in), `OrderHttpIT` 38 -> 37 (4 out, 3 in), `CheckoutQuoteIT`
  73 -> 73 (the binding refusal test swapped back for the ratified disagreement test), `ModuleBoundaryTest` 66 -> 67; no other
  test class changed count. Eight mutation checks were each killed: money-mismatch rejection reintroduced, revoke-after-quote
  rejected, benefit-gained-after-quote rejected, the DTO consuming `OrderMoneySnapshot` through a nested record (the PR #40 shape,
  now caught by the hardened rule), a synthesized legacy Order money, an identity field leaking into the public money,
  `PRICE_CHANGED` bypassed, and `PAYABLE_CHANGED` re-added to the OpenAPI error enum.
- **2026-10-02** — merged-`main` verification of PR #41 (squash `22416e7a90aa36bc557d0926cc4b44dd7007a6b4`, push CI run
  `37024797843` on Java 21): **BUILD SUCCESS**, 2227 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 66/66.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr22-benefits-config-hardening` (based on `main`
  `f98aafb67d946c04a7a7141f164e1d0998ab759b`): **BUILD SUCCESS**, 2227 tests, 0 failures / 0 errors / 0 skipped (2216
  baseline + 11 `BenefitsPlanCrossValidationTest`); `ModuleBoundaryTest` 66/66 (no new rule); no customer DTO/OpenAPI
  schema change (the `customer-checkout` tag description wording was refreshed). Seven mutation checks (unknown planId
  accepted, unknown planVersion accepted, empty Benefits config made invalid, every plan version required to have a rule,
  orphan rule silently ignored, planId-only matching, and a wrong rule count) were each killed by the tests above; the
  startup rule-count LOG line itself has no text-matching test (the count seam, `ruleCount()`, is tested).
- **2026-10-02** — merged-`main` verification of PR #39 (squash `f98aafb67d946c04a7a7141f164e1d0998ab759b`, push CI run
  `37011600092` on Java 21): **BUILD SUCCESS**, 2216 tests, 0 failures / 0 errors / 0 skipped; `ModuleBoundaryTest` 66/66.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr20a-order-money-snapshot` (based on `main`
  `4838096`): **BUILD SUCCESS**, 2188 tests, 0 failures / 0 errors / 0 skipped (2173 baseline + 9 `OrderMoneySnapshotTest`
  + 5 new `OrderBenefitsPlacementIT` tests + 1 ArchUnit rule); `ModuleBoundaryTest` 64/64; the public Order DTO/OpenAPI are
  unchanged (`swagger-cli validate` passes; `OrderHttpIT` unchanged). Nine mutation checks (the discount forced to 0 on an
  APPLIED order, payable computed as the subtotal, a discount above the subtotal allowed, legacy absence synthesized as the
  subtotal, reconstruction ignoring the payable formula, the money discount diverging from the Benefit snapshot, and money
  recomputed and used on the fast replay, the in-transaction replay and duplicate-key recovery) were each killed by the
  tests above.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr19a2-checkout-benefit-preview-projection`
  (based on `main` `30dea72`): **BUILD SUCCESS**, 2173 tests, 0 failures / 0 errors / 0 skipped (2157 baseline + 9
  `CheckoutBenefitPreviewTest` + 2 `CheckoutQuoteContractTest` + 4 new `CheckoutQuoteIT` tests + 1 ArchUnit rule);
  `ModuleBoundaryTest` 63/63; `swagger-cli validate` of the OpenAPI passes. Six mutation checks (the internal reason
  exposed publicly, NO_MEMBERSHIP and NOT_ELIGIBLE projecting differently, the discount recomputed from the rate, a legacy
  quote synthesizing `applied:false`, the DTO depending on the Benefits port, and `subtotalPaise` replaced by
  subtotal-minus-discount) were each killed by the tests/rules above.
  The contract-test hardening additionally kills four YAML mutations of the authoritative contract: the not-applied
  discriminator enum flipped to `[true]` and `type: boolean` removed from the applied discriminator (both survived the
  earlier test), plus `type: boolean` removed from the not-applied discriminator and the applied enum flipped to
  `[false]`.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr19a1-checkout-benefits-snapshot`
  (based on `main` `4486044`, after the independent-review hardening): **BUILD SUCCESS**, 2157 tests, 0 failures /
  0 errors / 0 skipped (2126 baseline + 11 `CheckoutBenefitSnapshotTest` + 17 new `CheckoutQuoteIT` tests + 3 net
  ArchUnit rules); `ModuleBoundaryTest` 62/62; the public Checkout DTO/OpenAPI are unchanged (the exact public field
  set is still asserted). Mutation checks killed: Checkout using the transactional port, replay re-evaluating
  Benefits, GET re-evaluating Benefits, NO_BENEFIT not persisted, a discount overwriting the canonical subtotal, a
  legacy missing snapshot treated as corruption, and (added by the hardening: two direct tests, a durable quote found by
  the IN-TRANSACTION replay check and by duplicate-key recovery, neither of which evaluates Benefits again and both
  of which return the stored winner's snapshot) a Benefits re-evaluation, discarded or used, in either path.
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr18a1-order-benefits-snapshot`
  (based on `main` `cb2097f`, after the independent-review hardening): **BUILD SUCCESS**, 2126 tests, 0 failures /
  0 errors / 0 skipped (2090 baseline + 12 `OrderBenefitSnapshotTest` + 20 `OrderBenefitsPlacementIT` + 1 Benefits
  accessor test + 3 ArchUnit rules); `ModuleBoundaryTest` 59/59; the public Order/Checkout/Cart DTOs and OpenAPI are
  unchanged. Mutation checks killed: standalone-port dependency, NO_BENEFIT snapshot not persisted, APPLIED discount
  zeroed, replay re-evaluating Benefits, discount overwriting the canonical subtotal, reconstruction accepting a
  missing membership id / wrong subtotal, Benefits evaluated on the in-transaction replay branch, duplicate-key
  recovery re-evaluating Benefits, and a planted `value()` on another allowed Membership type (the narrowed rule
  catches it; the previous name-only rule did not).
- **2026-10-02** — `./mvnw clean test` on Java 21 + Docker on `feature/pr17a1-benefits-foundation`
  (based on `main` `99e2d1b`, after the independent-review hardening): **BUILD SUCCESS**, 2090 tests, 0 failures /
  0 errors / 0 skipped (2028 baseline + 44 Benefits unit/binding tests + 9 `BenefitsEvaluationIT` + 9 ArchUnit
  rules); `ModuleBoundaryTest` 56/56. Mutation checks (each killed by the tests/rules named in the PR): removing the
  economic-at-threshold validation, exclusive threshold, latest-version fallback, Membership outage mapped to
  no-benefit, a forbidden Membership implementation dependency; plus the earlier rounding, bps-bound, version-lookup,
  transactional-metric and Benefits -> Membership write mutations and, in a scratch export, one mutation per
  remaining ArchUnit rule.
- **2026-10-01** — `./mvnw clean test` on Java 21 + Docker on `feature/pr16a3-membership-termination`
  (based on `main` `580633a`): **BUILD SUCCESS**, 2028 tests, 0 failures / 0 errors / 0 skipped
  (1989 baseline + 24 `MembershipTerminationIT` + 4 `MembershipTerminationConcurrencyIT` + 7 reconstruction
  tests + 4 ArchUnit rules); `ModuleBoundaryTest` 47/47.
- **2026-10-01** — `./mvnw clean test` on Java 21 + Docker on `feature/pr16a2-membership-entitlement-read`
  (based on `main` `d32a23f`): **BUILD SUCCESS**, 1989 tests, 0 failures / 0 errors / 0 skipped
  (1951 baseline + 28 `MembershipEntitlementIT` + 3 `MembershipRepositoryIT` + 7 ArchUnit rules, after the
  ACTIVE-row and open-slot integrity hardening); `ModuleBoundaryTest` 43/43.
- **2026-10-01** — `./mvnw clean test` on Java 21 + Docker on `feature/pr16a1-membership-write-foundation`
  (based on `main` `0cdcc97`): **BUILD SUCCESS**, 1951 tests, 0 failures / 0 errors / 0 skipped
  (1854 baseline + 84 Membership + 13 ArchUnit rules); `ModuleBoundaryTest` 36/36.
- **2026-09-30** — same branch after the optional-coordinates fix: **BUILD SUCCESS**, 1854 tests,
  0 failures / 0 errors / 0 skipped (1840 + 7 `OrderAddressSnapshotTest` + 5 domain + 2 HTTP); `ModuleBoundaryTest`
  23/23; OpenAPI valid and the generated export unchanged (no public contract change).
- **2026-09-30** — `./mvnw clean test` on Java 21 + Docker on `feature/pr15a2-customer-order-http`
  (based on `main` `e8d4d45`): **BUILD SUCCESS**, 1840 tests, 0 failures / 0 errors / 0 skipped
  (1807 baseline + 31 `OrderHttpIT` + 2 ArchUnit rules); `ModuleBoundaryTest` 23/23; OpenAPI validates
  (`swagger-cli validate`).
- **2026-09-30** — `./mvnw clean test` on Java 21 + Docker on `feature/pr15a1-cod-order-domain`
  (based on `main` `611829c`): **BUILD SUCCESS**, 1801 tests, 0 failures / 0 errors / 0 skipped
  (1764 baseline + 37 new in `OrderPlaceCodIT`); `ModuleBoundaryTest` 21/21.
- **2026-09-30** — same branch after the duplicate-key recovery hardening: **BUILD SUCCESS**, 1807 tests,
  0 failures / 0 errors / 0 skipped (+6 duplicate-recovery tests).
- **2026-09-29** — `./mvnw clean test` on Java 21 + Docker on `feature/pr14a-inventory-reservation`
  (based on `main` `8b4fabb`, after the final clock-authority fix): **BUILD SUCCESS**,
  1688 tests, 0 failures / 0 errors / 0 skipped (1625 baseline + 63 new).
- **2026-09-29** — `./mvnw clean test` on Java 21 + Docker on `feature/pr14a-inventory-reservation`
  (based on `main` `8b4fabb`, after final expiry/port-contract hardening): **BUILD SUCCESS**,
  1685 tests, 0 failures / 0 errors / 0 skipped (1625 baseline + 60 new).
- **2026-09-29** — `./mvnw clean test` on Java 21 + Docker on `feature/pr14a-inventory-reservation`
  (based on `main` `8b4fabb`, after M1-M4 hardening): **BUILD SUCCESS**, 1665 tests, 0 failures / 0 errors / 0 skipped (1625 baseline + 40 new).
- **2026-09-29** — `./mvnw clean test` on Java 21 + Docker on `feature/pr13b-checkout-address-provenance`
  (based on `main` `1f73668`): **BUILD SUCCESS**, 1625 tests, 0 failures / 0 errors / 0 skipped (1617 baseline + 8 new).
- **2026-09-29** — `./mvnw clean test` on Java 21 + Docker on `feature/pr13a-checkout-quote`
  (based on `main` `5c7e4df`): **BUILD SUCCESS**, **1589 tests, 0 failures / 0 errors / 0 skipped**.
- **2026-09-28** — `./mvnw clean test` on Java 21 + Docker on `feature/pr11d-auth-transaction-retry-safety`
  (based on `main` `ce868f4`): **BUILD SUCCESS**, **1557 tests, 0 failures / 0 errors / 0 skipped**.
- **2026-09-28** — `./mvnw clean test` on Java 21 + Docker at `main` `ce868f4` (post-PR-12C baseline
  for PR-11D): **BUILD SUCCESS**, **1544 tests, 0 failures / 0 errors / 0 skipped**; merged-main CI run
  `36453966063` green.
- **2026-09-28** — `./mvnw clean test` in `services/catalog-service` on Java 21 + Docker, on
  `feature/pr12b-customer-address` at head `c443e22` (based on `main` `9ae2f2d`): **BUILD SUCCESS**,
  **1478 tests, 0 failures / 0 errors / 0 skipped**; merged-main CI run `36396355618` green.
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
