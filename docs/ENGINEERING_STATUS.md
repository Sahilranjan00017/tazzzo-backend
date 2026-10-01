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
    No TTL, history or expiry-scan index.
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
    `tazzzo.membership.plans` configuration is identical across environments as intended; (4) the deployed
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

## In review (NOT merged)

- **PR-16A-3 — Membership termination** (`com.tazzzo.membership`): **IN REVIEW**. The last Membership
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

## Follow-up debt (recorded)

- **Non-Auth `tx.run` result-holder audit (PR-11D, no action taken):** `AttributeAuthoringService`
  (`version[]`, two sites), `EvidenceService` (`outcome[]`), `RollupService.purge` (`deleted[]`) and
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
- Cart (PR-12C): **COMPLETE** (PR #21). Auth transaction retry hardening (PR-11D): **COMPLETE** (PR #22). Checkout (PR-13A): **COMPLETE** (PR #23). Checkout provenance (PR-13B): **COMPLETE** (PR #24, squash `8b4fabb9b82e4e3502202ffaff1aadd35f9ba311`). Inventory Reservation lifecycle (PR-14A): **COMPLETE** (PR #25, squash `44438031022238334ecdf3995ba1096101e2e47f`). Order Foundation (PR-14B): **COMPLETE** (PR #26, squash `b620538e34afc35f7f080461750681b467ed4096`). Cart purchase-finalization seam (PR-15A-0): **COMPLETE** (PR #28, squash `611829c3649de3f5c37dec4ac5b375a1d8ef454e`). COD Order domain (PR-15A-1): **COMPLETE** (PR #29, squash `e8d4d45e88ad4935f7ad84a46ae65a671e50ba63`). Customer Order HTTP (PR-15A-2): **COMPLETE** (PR #30, squash `0cdcc97b8fcf5f7c079815b8cb276874635d15d6`; operational `orders`-count==0 deployment gate **PENDING**, not verified). Membership write foundation (PR-16A-1): **COMPLETE** (PR #31, squash `d32a23fb2e4b52fa8076de45a07bf3b60912b1e2`; Membership deployment gates **PENDING**, not verified). Membership entitlement read seam (PR-16A-2): **COMPLETE** (PR #32, squash `580633abbc8d162f45110443f503d4998f4caa48`). Membership termination (PR-16A-3): **IN REVIEW** (cancel-at-period-end and immediate revoke; internal only, no HTTP, no Benefits, no Payment). Benefits/Promotion: **NOT STARTED**. Payment: **NOT STARTED**. Real payment gateway: **NOT STARTED**.

## Next (ratified sequence)

1. **Membership Foundation** — PR-16A-1 write foundation (complete), PR-16A-2 entitlement read seam
   (complete), PR-16A-3 termination (cancel-at-period-end, immediate revoke; in review).
2. Benefits / Promotion engine (the only place discount percentages and thresholds will live).
3. Checkout + Order money-model upgrade.
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
