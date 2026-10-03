# DATABASE_COLLECTION_CONTRACTS — tazzzo-backend (DB-1)

Documentation / policy only. No validator, index, schema, configuration or application code is changed by DB-1.

## 1. Metadata and scope

| Item | Value |
|---|---|
| Repository | `Sahilranjan00017/tazzzo-backend` |
| Base | `origin/main` `52ab530ec80c8ae5488a7b537590cfaa81165298` (DB-0 merged, PR #47) |
| Audited source | service `services/catalog-service`; source facts are those of `f5b2cdd` (DB-0 audit SHA). The only commit between `f5b2cdd` and the base is the DB-0 document. |
| Source of truth | `docs/database/DATABASE_INVENTORY.md` (DB-0), with writer-side BSON types re-verified directly for `orders`, `checkout_quotes`, `memberships`, `inventory`, `price_current`, `price_events` (see the "Verification note" under §4) |
| Output | this file. It does not overwrite the DB-0 inventory. |
| Mongo access | none. No connection to any MongoDB. |
| Status vocabulary | **VERIFIED** (read from source), **UNVERIFIED** (needs live/Atlas evidence or not provable), **PROPOSED** (policy, not yet implemented), **DEFERRED** |

Path convention as in DB-0: `J` = `services/catalog-service/src/main/java/com/tazzzo`, `SB` = `J/catalog/schema/SchemaBootstrap.java`, `ESD` = `docs/ENGINEERING_STATUS.md`.

## 2. Approved architecture rulings used as contract constraints

These rulings were approved by the database owner after DB-0 and bind DB-1 through DB-3.

- **R1 — price history is retained.** Durable commercial/audit price history must not be accidentally deleted by rollup/purge. Only an explicitly proven rollup/purge-eligible event type may be deleted. Durable price-change ledger events survive. Unknown or legacy shapes are never purgeable by default. Prefer retention over destructive ambiguity. (Contract: §9.)
- **R3 — taxonomy/attribute reference data is versioned durable state.** Normal startup must not continuously rewrite existing attribute schemas. Initial reference data may be insert-if-absent. Later evolution uses an explicit, versioned, repeatable migration/update mechanism. (Contract: §10.)
- **R5 — no blind mutable/destructive evolution on every instance.** Safe, deterministic, idempotent *verification* is acceptable at runtime. Mutating schema/index/data evolution moves to a controlled migration/bootstrap process that is explicit, versioned, idempotent, observable, safe to retry, separately permissioned, and never racing destructive operations across instances. (Contract: §11.)
- The stale branch `feature/db1-collection-contracts` is **not** used; this document is derived from the fresh DB-0.

## 3. Policy definitions

### 3.1 Strictness classes (as used in DB-0 §14)

| Class | Meaning | Rule used here |
|---|---|---|
| **A STRICT** | Reconstruction rejects missing required fields, explicit null, non-numeric wrong types, unknown enums and invariant violations; **no value is defaulted or invented** | A only if **no** silent default exists on its read path. The only tolerance allowed is *numeric-width tolerance* (an integer field read via `get(x, Number.class).longValue()/intValue()` accepts any BSON numeric type and truncates a Double/Decimal128), and it must be **listed** per collection |
| **B LEGACY-COMPATIBLE STRICT** | As A, plus explicitly enumerated historical shapes are accepted and **preserved** (never synthesized). Listed *absent≡null* quirks and listed numeric-width tolerance are permitted because they do not default or invent a business value | Each accepted legacy shape, quirk and tolerance is listed in §6 |
| **C PERMISSIVE** | Any value silently defaulted (`missing → false/0/null/empty/unlimited`), fields tolerated or unchecked, or failures surfacing as raw exceptions or invisible | Every silent fallback is listed per collection. **A collection with any read-path default is C even if its write path is strict** |
| **D OPERATIONAL/TEMPORARY** | Short-lived or work-coordination state; correctness is by filters/CAS, not full reconstruction | Retention/TTL stated |

"Strict" is never claimed for a collection that silently defaults any value. Classes were re-derived under this rule after independent review (see §5 note); the earlier draft classed `products`, `product_card_base` and `domain_events` more favourably than the rule allows.

### 3.2 Validator decision vocabulary

- **YES** — a server-side `$jsonSchema` validator is the recommended end state, with a contract proposed here, to be implemented only after the preconditions in §12 hold.
- **NO** — a server-side validator is not recommended (reason stated).
- **DEFER** — a validator may be right, but a prerequisite is missing (usually a discriminator, a migration mechanism, live-data evidence, or an unsettled contract).

Every YES/DEFER is **PROPOSED**. DB-1 implements **no** validator.

### 3.3 MongoDB validation semantics that the policy relies on

- `validationLevel: strict` — validator runs on every insert and every update of any document (including pre-existing non-conforming documents, which then cannot be updated unless the update makes them conform).
- `validationLevel: moderate` — validator runs on inserts, and on updates only of documents that already conform; pre-existing non-conforming documents can still be updated.
- `validationAction: error` rejects the write; `warn` logs only.
- `$jsonSchema` cannot express cross-field arithmetic or calendar rules (e.g. `payable = subtotal − discount`, membership `validUntil` calendar anchor); where a cross-field check is needed it may use `$expr` for simple comparisons only. Application reconstruction remains the authority for formulas.
- A validator added later via `collMod` does not retroactively reject existing documents; existing data conformance must be measured first (§12).

## 4. Contract conventions (VERIFIED unless noted)

| Topic | Convention | Evidence |
|---|---|---|
| Driver | raw sync driver, `Document` mapping, no POJO codecs, no Spring Data | DB-0 §2 |
| Money | `int64` paise (BSON Long). Fields named `*Paise` (customer/membership domains) or `*_paise` (catalogue/pricing). No Decimal128, no double money | DB-0 §3; `OrderRepository.toDocument`, `PricingService.currentDoc` |
| Currency | string `"INR"` only (`Currency` enum has one value). In quotes/orders it is a top-level field; there is **no** currency inside `money`/`benefits` sub-documents; memberships store it as `planCurrency` | DB-0 §16 |
| Timestamps | BSON Date. OTP/grant/session/customer repositories write `java.time.Instant`, the rest write `Date.from(...)`; reads use `getDate`. Raw BSON type of the `Instant` writers is **UNVERIFIED** by test | DB-0 §3 |
| Versions/CAS | `version` is `Long` in customer, order, membership, price, inventory, media and service-area documents, `Int32` in `products` and `taxonomy_nodes`. CAS is by filter on `version` with `$inc` | DB-0 §3 |
| Counts/quantities | `Int32` for `orders.lines.quantity`, `checkout_quotes.items.quantity`, and `itemCount`; **`Long` for `inventory_reservations.items[].quantity`** (`InventoryReservationItem(String skuId, long quantity)`, written `InventoryReservationRepository:83`) and for stock counters; `media_refs` `width`/`height` are nullable `Int32` | verified writer types |
| Naming | camelCase in customer/membership/order/inventory-reservation collections; snake_case in catalogue, pricing, inventory, media, serviceability and event collections | DB-0 §3 |
| Ids | opaque prefixed ids: `OTP_`, `GRANT_`, `CUS_`, `SES_`, `ADDR_`, `CHKQ_`, `ORD_`, `MBR_` (patterns in DB-0/`Opaque*Id` classes); catalogue ids `TZP-`, `EV-`, `TZS-/TZC-/TZB-/TZV-` | DB-0 §3 |
| Unknown fields | ignored on read in every customer/membership repository; rejected on write only for `products` (`additionalProperties:false`). Strict key-set checks exist only inside nested benefit/money snapshots and `actor` | DB-0 §14 |
| Absent vs null | optional facts are written **absent**, not null, for `memberships` (`cancelRequestedAt`, `revokedAt`, `openTerm`), `orders`/`checkout_quotes` (`benefits`, `money`, `confirmed*`). Exceptions that write explicit null: OTP challenge activation fields, `customer_address_state.defaultAddressId`, address optional text/coordinates, `price_current`/`price_events` `effective_from`/`effective_to` | verified writers |

Verification note (§1): `Order` record: `long version`, `long addressVersion`, `int itemCount`, `long subtotalPaise`; `OrderLine`: `int quantity`, `long unitPricePaise`, `long lineTotalPaise`; `CheckoutQuote`: `long cartVersion`, `long addressVersion`, `int itemCount`, `long subtotalPaise`; `Membership`: `long version`, `int planVersion`, `int planPeriodMonths`, `long periodCount`; `InventoryRecord`: `long onHand/reserved/lowStockThreshold`; `InventoryReservationItem`: `long quantity`; inventory insert writes `reserved` as `0L`; `price_current` writes `selling_price_paise`/`mrp_paise` as long, `version` long. `inventory.low_stock_threshold` and `inventory.max_purchasable` are `long` (`SetInventoryCommand:19-20`) and are written via `append`/`Updates.set` of a long. Numeric-width tolerance on reads is recorded per collection in §6.

---

## 5. Collection roster, classification and validator decision

All 49 collections of `SchemaBootstrap.COLLECTIONS` (`SB:26-114`). "Unused" = created by bootstrap with no reader/writer in main. Retention "none" means no TTL and no code that removes rows.

| # | Collection | Owner | Class | Strictness | Validator | Retention |
|---|---|---|---|---|---|---|
| 1 | `products` | catalog | authoritative | C (read-path defaults; DB validator exists on the write side, §7.1/§8) | **EXISTS** (create-time only) | none |
| 2 | `gtin_registry` | catalog | authoritative | C | DEFER | none |
| 3 | `identity_keys` | catalog | authoritative | C | DEFER | none |
| 4 | `canonical_keys` | catalog | authoritative | C | DEFER | none |
| 5 | `discriminating_attributes` | catalog (reference) | authoritative | C | NO | none |
| 6 | `brands` | — (unused) | — | C | NO (retire/decide in DB-2) | none |
| 7 | `product_events` | catalog | event | C | DEFER | none |
| 8 | `classification_history` | catalog | event | C | DEFER | none |
| 9 | `evidence` | catalog | authoritative | C | DEFER | none |
| 10 | `evidence_links` | catalog | derived/relationship | C | DEFER | none |
| 11 | `work_queue` | catalog/commerce | operational | **D** | NO | none (only `card_rebuild` rows deleted) |
| 12 | `offers_current` | catalog | authoritative (raw input) | C | NO | none |
| 13 | `catalogue_releases` | catalog (taxonomy) | authoritative | C | DEFER | none |
| 14 | `batches` | — (unused) | — | C | NO | none |
| 15 | `campaigns` | — (unused) | — | C | NO | none |
| 16 | `campaign_membership` | — (unused) | — | C | NO | none |
| 17 | `aliases` | catalog (taxonomy) | authoritative (reference) | C | DEFER | none |
| 18 | `variant_groups` | — (unused) | — | C | NO | none |
| 19 | `marketplace_crosswalks` | — (unused) | — | C | NO | none |
| 20 | `system_config` | catalog | authoritative | C | DEFER | none |
| 21 | `attachment_registry` | catalog | authoritative | C | NO | none |
| 22 | `taxonomy_nodes` | catalog (taxonomy) | authoritative | C | DEFER | none |
| 23 | `taxonomy_snapshot_nodes` | catalog (taxonomy) | snapshot | C (reader fails closed) | DEFER | none |
| 24 | `attribute_definitions` | catalog | authoritative (versioned) | C | DEFER | none (superseded kept) |
| 25 | `attribute_schemas` | catalog | authoritative (versioned) | C | DEFER | none |
| 26 | `id_sequences` | catalog | operational (counter) | **D** | NO | none |
| 27 | `price_current` | pricing | authoritative | C | **YES (PROPOSED)** | none |
| 28 | `price_events` | pricing/catalog | event (two shapes) | C | **DEFER** (R1 discriminator first) | **TARGET: retain (R1). CURRENT: rolled rows hard-deleted hourly (non-compliant)** |
| 29 | `price_rollups` | catalog | derived | C | NO | none |
| 30 | `inventory` | inventory | authoritative | C | **YES (PROPOSED)** | none |
| 31 | `inventory_reservations` | inventory | authoritative | **A** | **YES (PROPOSED)** | none (never deleted) |
| 32 | `media_refs` | media | authoritative | C | DEFER | none |
| 33 | `service_areas` | serviceability | authoritative | C | DEFER | none |
| 34 | `product_card_base` | commerce read | derived (rebuildable) | C (partial fail-fast; §7.4) | NO | none (rebuildable) |
| 35 | `consumer_projection_policy` | catalog consumer | authoritative (config) | **A** | NO | none |
| 36 | `node_events` | catalog (taxonomy) | event | C | DEFER | none |
| 37 | `domain_events` | common/audit | event | C (no reader/reconstruction path in main; actor codec is strict but unused) | DEFER | none |
| 38 | `rollup_state` | — (unused) | — | C | NO | none |
| 39 | `customer_otp_challenges` | auth.otp | operational | **D** | NO | TTL (two indexes) |
| 40 | `customer_otp_verified_grants` | auth.otp/session | operational | **D** | NO | TTL |
| 41 | `customers` | auth.session | authoritative | C | DEFER | none |
| 42 | `customer_sessions` | auth.session | authoritative | C (fail-closed) | NO | TTL (cleanup only) |
| 43 | `customer_profiles` | customer.profile | authoritative | C | DEFER | none |
| 44 | `customer_addresses` | customer.address | authoritative | C | DEFER | none |
| 45 | `customer_address_state` | customer.address | authoritative (control) | C | DEFER | none |
| 46 | `customer_carts` | customer.cart | authoritative | C (marker A) | DEFER | none (by design) |
| 47 | `checkout_quotes` | customer.checkout | snapshot | **B** | **YES (PROPOSED)** | none (forever) |
| 48 | `orders` | customer.order | authoritative + snapshot | **B** | **YES (PROPOSED)** | none (forever) |
| 49 | `memberships` | membership | authoritative | **B** | **YES (PROPOSED)** | none (by design) |

**Tallies (49):** A = 2 (`inventory_reservations`, `consumer_projection_policy`); B = 3 (`orders`, `checkout_quotes`, `memberships`); D = 4 (`work_queue`, `id_sequences`, `customer_otp_challenges`, `customer_otp_verified_grants`); C = 40 (of which 7 are unused collections). 2 + 3 + 40 + 4 = 49. Validators: 1 exists (`products`); **YES (proposed) 6**; **DEFER 24**; **NO 18** (7 of the NOs are the unused collections); 1 + 6 + 24 + 18 = 49.

**Reclassification note (post-review):** an earlier draft classed `products`, `product_card_base` and `domain_events` as A/B. Under the §3.1 rule they are C: `products` has read-path defaults (`CatalogCardReader` missing `version`→0 and missing classification→null vertical; `ProductController.toResponse` stringifies nulls as `"null"`); `product_card_base.fromDocument` validates only `source_versions`, `catalog_version`, `projection_version` and `price_status` and silently reads a missing `price_version`/`media_version` as null; `domain_events` has no reader in main and `ActorDocuments.fromEvent` has no caller, so no reconstruction path exists to be strict. Their *write-side* contracts (the `products` validator, the strict `ActorDocuments` codec) remain as documented.

---

## 6. Detailed contracts — critical collections

For each: required fields and BSON types as written by code; optional; enums; reconstruction strictness and every silent fallback; legacy shapes; unknown-field behaviour; transactions; query patterns; proposed validator.

### 6.1 `orders` — B LEGACY-COMPATIBLE STRICT

- **Owner / class:** `customer.order`; authoritative + snapshot; immutable (a CREATED row or a CONFIRMED row is written whole by insert; no update path exists in main).
- **`_id`:** string `ORD_*` (`^ORD_[A-Za-z0-9_-]{6,64}$`), generated before the Tx (idempotent across retries).
- **Business identity:** `(customerId, quoteId)` — unique index `order_one_per_quote`. No separate idempotency-key field.
- **Required (as written, `OrderRepository.toDocument`):**
  `customerId` string; `quoteId` string (`CHKQ_*`); `status` string ∈ {`CREATED`,`CONFIRMED`}; `paymentMethod` string ∈ {`COD`}; `version` **long** (CREATED=1, CONFIRMED=2); `addressId` string; `addressVersion` **long**; `addressSnapshot` document; `lines` array (≥1) of `{skuId string, title string, brandCode string|null, quantity int ≥1, unitPricePaise long ≥0, lineTotalPaise long ≥0}`; `itemCount` int; `subtotalPaise` long ≥0; `currency` string `"INR"`; `reservationId` string; `createdAt` Date; `updatedAt` Date.
- **`addressSnapshot`:** `{label ∈ HOME|WORK|OTHER, recipientName, recipientPhone (+91…), addressLine1, addressLine2|null, landmark|null, city, state, postalCode ^[1-9][0-9]{5}$, latitude double|null, longitude double|null}`. Coordinates both-null or both-valid. PII.
- **Optional (absent, never null):** `benefits`, `money`; `confirmedPaymentCondition` ∈ {`COD_DUE`} and `confirmedAt` Date (present **only** for CONFIRMED).
  - `benefits` NO_BENEFIT `{outcome, eligibleSubtotalPaise long, noBenefitReason ∈ NO_MEMBERSHIP|NO_RULE|NOT_ELIGIBLE}`; APPLIED `{outcome, eligibleSubtotalPaise, discountPaise long, discountBps int 1..10000, membershipId (MBR_*), planId, planVersion int}`. Exact key-set required.
  - `money` `{merchandiseSubtotalPaise, benefitDiscountPaise, payablePaise}` all long; **no currency subfield**; `payable = merchandiseSubtotal − benefitDiscount` re-derived and compared on read.
- **Cross-field invariants (application-enforced in `Order` constructor/codecs, not expressible in `$jsonSchema`):** `lineTotal = unit × qty`; no duplicate `skuId`; `itemCount = Σ quantity`; `subtotal = Σ lineTotal`; `benefits.eligibleSubtotal = subtotal`; `money` requires `benefits`; `money.merchandiseSubtotal = subtotal`; `money.benefitDiscount` = benefits discount (0 if NO_BENEFIT); `createdAt ≤ updatedAt`; CREATED ⇒ version 1 and no `confirmed*`; CONFIRMED ⇒ version 2, `paymentMethod=COD`, `COD_DUE`, `confirmedAt ≥ createdAt`, `updatedAt ≥ confirmedAt`.
- **Not re-verified on read:** the `floor(subtotal × bps / 10000)` discount formula (`BenefitEvaluator` only at placement).
- **Strictness / fallbacks:** `status`, `paymentMethod`, `version`, `lines`, `addressSnapshot`, numerics, dates are required with no default (missing → exception). String fields of the wrong type → `ClassCastException`; bad enum → IAE. **Numeric-width tolerance (listed):** `lines[].quantity`, `unitPricePaise`, `lineTotalPaise`, `itemCount`, `subtotalPaise` are read with `get(x, Number.class).intValue()/longValue()` and `version`/`addressVersion` via `requireLong` (`instanceof Number`), so a stored Double or Decimal128 is accepted and truncated (`OrderRepository:109-110,129-131,155-161`). The benefit/money sub-document codecs do reject non-integral values (`requireIntegral`). **Silent leniency (documented):** explicit-null `confirmedPaymentCondition`/`confirmedAt` is treated as absent (`OrderRepository.toOrder` `:118,133,135`), although the constructor still rejects inconsistent combinations. Explicit-null `benefits`/`money` is **not** lenient (throws).
- **Legacy shapes accepted:** `benefits` absent ⇒ null (never "no benefit"); `money` absent ⇒ null (never a zero payable; DTO omits `money`). **Not accepted:** rows lacking `version`/`paymentMethod` (pre-PR-15A-1): deployment is gated on `orders` count == 0 (ESD:541-579, **UNVERIFIED** live).
- **Unknown top-level fields:** ignored on read; stored as-is if present.
- **Retention:** forever. No TTL (by design, `SB:104-109`). Contains PII (`addressSnapshot`).
- **Transactions:** written only in the COD placement Tx (`OrderService.finishCod`/`createOrder`), after reserve+consume+cart finalization; duplicate key on `(customerId,quoteId)` recovered by re-reading the winner (`recoverFromDuplicateKey`), otherwise INTEGRITY_FAILURE.
- **Query/write patterns:** insert; `findByCustomerAndQuote` (index); `findOwnedById` (`_id`). No update path.
- **Validator: YES (PROPOSED).** Rationale: insert-only, invariants stable, a typed contract is cheap and catches writer bugs. **Proposed contract:** required list/types above; `additionalProperties` **true** (do not reject unknown fields — the application ignores them and a future additive field must not break rollout); `lines.minItems 1`; enums as above; `benefits`/`money` optional but if present exact sub-shape (`oneOf` NO_BENEFIT/APPLIED); `confirmed*` both-or-neither via `oneOf` on `status`. **Level:** `moderate` (so any pre-existing non-conforming row can never block the collection), **action:** `error`. **Cross-field formulas stay application-side.**
- **Legacy compatibility:** validator must allow `benefits` and `money` absent. Must not require them.
- **Live-data risk:** orders gate 1 is UNVERIFIED; if rows exist from before PR-15A-1 they lack required fields. With `moderate` they remain readable by the DB but still fail application reconstruction (pre-existing behaviour).
- **Migration requirement:** none for data (no backfill is permitted — historical facts are not synthesized). A validator rollout is a `collMod` performed by the controlled migration (§11), after gate 1 evidence.
- **Rollout requirement:** staging first with a conformance scan (`$jsonSchema` negated `find` count == 0 or the non-conforming set understood); `validationAction: warn` observation window optional; then `error`.

### 6.2 `checkout_quotes` — B LEGACY-COMPATIBLE STRICT

- **Owner / class:** `customer.checkout`; snapshot; immutable; never deleted or mutated by expiry (expiry is a clock check → 410).
- **`_id`:** `CHKQ_*`. **Identity:** unique `(customerId, idempotencyKeyDigest)` (`checkout_quote_one_per_idempotency_key`).
- **Required:** `customerId` string; `idempotencyKeyDigest` string (lowercase hex SHA-256 of the raw key — **the raw `Idempotency-Key` is never stored**); `fingerprint` string (hex SHA-256 of `"v1|"+cartVersion+"|"+addressId`); `cartVersion` long; `addressId` string; `addressVersion` long; `items` array of `{skuId, quantity int, unitPricePaise long, lineTotalPaise long}`; `itemCount` int; `subtotalPaise` long; `currency` `"INR"`; `createdAt` Date; `expiresAt` Date.
- **Optional (absent, never null):** `benefits` (NO_BENEFIT or APPLIED **without** membership/plan identity), `money` (as in orders).
- **Invariants:** as orders for lines/subtotal/itemCount; `createdAt < expiresAt`; `benefits.eligibleSubtotal = subtotal`; `money` requires `benefits`; `payable` re-derived on read.
- **Strictness / fallbacks:** `addressVersion` absent ⇒ IAE → 500 (no default). `benefits`/`money` present-but-invalid (explicit null, wrong type, foreign/missing keys via `Set.equals`, bad enum) ⇒ IAE → 500. **Silent leniency:** a missing `fingerprint` compares unequal and surfaces as 409 IDEMPOTENCY_CONFLICT rather than a corruption error. Required with no default: `items`, `cartVersion`, `itemCount`, `subtotalPaise`, `createdAt`, `expiresAt`, `currency` (must equal INR). **Numeric-width tolerance (listed):** item `quantity`/`unitPricePaise`/`lineTotalPaise` are read via `get(x, Number.class)` (`CheckoutQuoteRepository:101-103,117-119`), so a Double/Decimal128 is accepted and truncated; `benefits`/`money` sub-documents reject non-integral values.
- **Legacy shapes accepted:** `benefits` absent (pre-Benefits quote) and `money` absent ⇒ null, HTTP omits previews, a legacy quote still places an Order normally (Order never reads quote money).
- **Unknown top-level fields:** ignored.
- **Retention:** forever (no TTL). **Transactions:** written only by `CheckoutService.persist` (Tx); reads for replay; Benefits are evaluated **outside** the Tx and stored as advisory.
- **Query/write patterns:** insert; `findByIdempotency`; `findOwned(Quote)` by `_id`+`customerId`.
- **Validator: YES (PROPOSED)** — same posture as `orders` (`additionalProperties:true`, `moderate`, `error`, `benefits`/`money` optional, cross-field formulas application-side). **Requirement:** `addressVersion` required in the proposed contract only after measuring whether pre-PR-13B quotes exist (**UNVERIFIED**); until then it is a recommended-required field. **Migration:** none; no backfill.

### 6.3 `memberships` — B LEGACY-COMPATIBLE STRICT

- **Owner / class:** `membership`; authoritative; one document per term with an embedded plan **snapshot**.
- **`_id`:** `MBR_*`. **Identity:** unique `(grantSource, grantRef)`; at most one open term per customer via partial unique `{customerId}` where `openTerm:true`.
- **Required:** `customerId` string; `status` ∈ {`ACTIVE`,`EXPIRED`,`REVOKED`}; `version` long ≥1; `grantSource` ∈ {`INTERNAL_GRANT`}; `grantRef` string `^[A-Za-z0-9._:-]{1,128}$`; `planId` string `^[A-Z][A-Z0-9_]{2,63}$`; `planVersion` int; `planPricePaise` long **> 0** (`Membership:51` rejects ≤0 and non-INR); `planCurrency` `"INR"`; `planPeriodMonths` int 1..120; `billingZoneId` string `"Asia/Kolkata"`; `periodCount` long; `validFrom`, `validUntil`, `createdAt`, `updatedAt` Date.
- **Optional (absent, never null):** `openTerm` boolean **only `true`** (written only while ACTIVE; `$unset` on EXPIRED/REVOKED, never `false`/null); `cancelRequestedAt` Date; `revokedAt` Date.
- **Invariants (application-enforced):** `createdAt = validFrom`; `validUntil = MembershipBillingCalendar.validUntil(validFrom, periodCount, planPeriodMonths)` (Asia/Kolkata calendar-month anchor); ms precision; `revokedAt` present iff REVOKED; `cancelRequestedAt`/`revokedAt` ∈ `[validFrom, validUntil)`; REVOKED ⇒ `updatedAt = revokedAt`, version ≥2; ACTIVE with cancel request ⇒ version ≥2, `updatedAt = cancelRequestedAt`; EXPIRED ⇒ version ≥2, `updatedAt ≥ validUntil`; ACTIVE ⇒ `openTerm:true`, terminal ⇒ no `openTerm`.
- **Strictness / fallbacks:** all strict, **no defaults**, Integer/Long only (`requireIntegral`); any `RuntimeException` → `MembershipFailure(INTEGRITY_FAILURE)`. No silent default found. Plan fields are **not** cross-checked against current config (the term snapshot is authoritative).
- **Legacy shapes accepted:** `cancelRequestedAt`/`revokedAt` absent (pre-PR-16A-3) ⇒ null, preserved; present-but-null/string/number throws.
- **Unknown top-level fields:** ignored. **Retention:** none by design (`MembershipRepositoryIT:76` asserts no TTL). **Sensitive:** none beyond `customerId`.
- **Transactions:** grant (`MembershipService.grantInSession`: read-by-ref → plan check → `expireIfDue` CAS of a stale open term → insert), cancel/revoke (CAS). Non-session reads pinned to `ReadPreference.primary()`; session reads follow the Tx.
- **Query/write patterns:** `findByGrantReference`, `findOpenByCustomer`, `findCurrentCandidateByCustomer` (limit 2, >1 ⇒ INTEGRITY_FAILURE), CAS updates.
- **Validator: YES (PROPOSED)** — types, enums, patterns, `planCurrency`/`billingZoneId` constants, `openTerm` as `{bsonType:bool, enum:[true]}`, `cancelRequestedAt`/`revokedAt` as Date (never null). `additionalProperties:true`. **Level** `moderate`, **action** `error`. Calendar/`updatedAt` formulas remain application-side. **Live-data risk:** gate 2 ("no pre-existing conflicting `memberships` collection/schema") is UNVERIFIED; a conflicting pre-existing collection must be inspected before any `collMod`. **Rollout:** after gate 2 evidence; the three existing indexes are already test-asserted.

### 6.4 `inventory` — C PERMISSIVE

- **Owner / class:** `inventory`; authoritative. **Identity:** unique `(sku_id, fulfillment_location_id)`. **`_id`:** ObjectId.
- **Required (as written, `InventoryService.setInventory`):** `sku_id` string; `fulfillment_location_id` string; `on_hand` long ≥0; `reserved` long ≥0 (insert writes `0L`); `low_stock_threshold` long; `max_purchasable` long; `version` long ≥1; `active` boolean (true on create; **no code ever changes it**); `source` string **or explicit null** (`SetInventoryCommand.source` is never validated and is written unconditionally, `InventoryService:111,125`); `created_at`, `updated_at` Date.
- **Invariants:** `reserved ≤ on_hand`; `on_hand ≤ 1,000,000`. Reserve filter `active=true AND (on_hand − reserved) ≥ qty` (`$expr`); consume needs `reserved ≥ qty AND on_hand ≥ qty`.
- **Strictness / fallbacks:** counters and `version` required (NPE if missing); `InventoryRecord` constructor throws on negative values / `reserved > onHand` / `version < 1`. **Silent default:** `active` missing ⇒ false (INACTIVE). Hence class **C**.
- **Legacy shapes:** none documented. **Unknown fields:** ignored. **Retention:** none. **Sensitive:** none.
- **Transactions:** `setInventory` (CAS on `version` with `reserved ≤ newOnHand`); reserve/release/consume primitives run inside the caller's Tx with a `product_events` row first. **Patterns:** point lookup by `(sku_id, fulfillment_location_id)` (unique index); CAS writes.
- **Validator: YES (PROPOSED).** Contract: required fields/types above; `on_hand`,`reserved` `{bsonType:long, minimum:0}`; `version` long ≥1; `active` bool; `$expr: {$lte:["$reserved","$on_hand"]}` is a candidate (simple comparison; `reserved ≤ on_hand` is a hard invariant), and it is compatible with every current write path (verified by source reading): reserve increments only `reserved` behind `(on_hand − reserved) ≥ qty` (`InventoryService:237-239`); release decrements only `reserved` behind `reserved ≥ qty` (`:259-260`); consume decrements both behind `reserved ≥ qty AND on_hand ≥ qty` (`:279-281`); `setInventory` requires `reserved ≤ newOnHand` (`:119`). It must still be proven by a Testcontainers run before adoption. The validator must allow `source` as `["string","null"]` (or the command must first reject a null source). **Level** `strict` is acceptable only after a conformance scan; default recommendation `moderate`. **Rollout:** conformance scan + test of every inventory write path against the validator in a Testcontainers run (new tests, DB-7).

### 6.5 `inventory_reservations` — A STRICT

- **Owner / class:** `inventory`; authoritative; **`_id`** opaque `InventoryReservationId`.
- **Required:** `orderId` string (unique index `inventory_reservation_one_per_order`); `fulfillmentLocationId` string; `items` array of `{skuId string, quantity` **`long`**`}` (≥1, sorted by skuId on reserve) — **not int**; `status` ∈ {`RESERVED`,`RELEASED`,`CONSUMED`}; `createdAt`, `expiresAt`, `updatedAt` Date; `fingerprint` string (SHA-256 of `v1|orderId|location|sorted items`).
- **Version/CAS:** none; transitions are CAS on `status` only (RESERVED→RELEASED, RESERVED→CONSUMED).
- **Strictness:** `toReservation` has no value fallback and fails loud (NPE on missing `items`, IAE on bad enum, record invariants) ⇒ **A with two listed tolerances:** (1) **numeric-width tolerance** — `items[].quantity` is read via `get("quantity", Number.class).longValue()` (`InventoryReservationRepository:101`), so a Double/Decimal128 is accepted and truncated; (2) a **missing `fingerprint`** reads as null and the idempotent re-reserve comparison `fingerprint.equals(existing.getString("fingerprint"))` is false, surfacing as `ALREADY_RESERVED_DIFFERENT_INPUT` rather than a corruption error (`InventoryReservationService:176-181`) — the same class of leniency as `checkout_quotes`.
- **Retention:** none; never deleted (`SB:99-103`). Expiry worker (`InventoryReservationExpiryWorker`, 30 s) is **disabled by default** (flag absent in `application.yml`).
- **Transactions:** header insert is the **last** write of the reserve Tx; idempotent by `orderId`+fingerprint (`ALREADY_RESERVED_DIFFERENT_INPUT` on mismatch); duplicate-key race resolved by `resolveDuplicateWinner`.
- **Patterns:** `findByOrderId`; `findExpiredBatch` (`status=RESERVED`, `expiresAt ≤ now`, sort `expiresAt`) served by `inventory_reservation_expiry`.
- **Validator: YES (PROPOSED).** Contract: required fields/types; `status` enum; `items.minItems 1`; `additionalProperties:true`; `moderate`/`error`. Low live-data risk (the header is inserted whole by one code path, then only `status`/`updatedAt` change by CAS `transitionStatus`, `InventoryReservationRepository:72-78`), but live existence is **UNVERIFIED**. The validator must use `long` for `items.quantity`.

### 6.6 `price_current` — C PERMISSIVE

- **Owner / class:** `pricing`; authoritative; `_id` ObjectId; **identity** unique `(sku_id, currency)`.
- **Required (as written, `PricingService.currentDoc`):** `sku_id` string; `currency` string `"INR"`; `selling_price_paise` **long** ≥0; `mrp_paise` **long** ≥ `selling_price_paise`; `version` **long** ≥1; `active` boolean (written true; **never set false by any code**); `source` string **or explicit null** (`UpsertPriceCommand.source` is never validated, `PricingService:315-343`; written unconditionally `:159,356,372`); `created_at`, `updated_at` Date.
- **Written explicit-null:** `effective_from` Date|**null**, `effective_to` Date|**null** (stored as null when absent — not absent).
- **Bounds in code:** `MAX_AMOUNT_PAISE = 1_000_000_000`; `effectiveFrom ≤ now`; `effectiveTo > now`; from inclusive, to exclusive.
- **Strictness / fallbacks:** missing paise/`version` ⇒ NPE (`asLong` `requireNonNull`); invalid `Price` (mrp < selling) ⇒ IAE. **Silent defaults:** `active` missing ⇒ false (INACTIVE); missing `effective_*` ⇒ null; read currency filter hard-wired to INR. Hence **C**.
- **Legacy shapes:** none. **Retention:** none. **Transactions:** `PricingService.upsertPrice` (ledger row first, then CAS/insert, optional `work_queue` rebuild); CAS miss or duplicate create rolls back the ledger row. No production caller exists in main today (DB-0 §13).
- **Patterns:** point and batch `$in` read by `(sku_id, currency)`; CAS update by `(sku_id, currency, version)`.
- **Validator: YES (PROPOSED).** Contract: types above; `effective_from`/`effective_to` `["date","null"]`; `source` `["string","null"]`; `currency` enum [`INR`]; `$expr {$gte:["$mrp_paise","$selling_price_paise"]}`; `selling_price_paise` min 0 max 1,000,000,000. **Level** `moderate`, **action** `error`. **Live-data risk:** low (no production writer today, so live data likely empty — **UNVERIFIED**). **Rollout:** best done before any production caller exists (cheapest moment).

### 6.7 `price_events` — C PERMISSIVE, two row shapes (R1)

See §9 for the retention contract. Row shapes (VERIFIED):

- **Shape P (paise ledger)** — `PricingService.ledgerRow`: `product_id` (= sku id), `sku_id`, `currency`, `selling_price_paise` long, `mrp_paise` long, `version` long, `effective_from` Date|null, `effective_to` Date|null, `source`, `ts` Date; optional `actor` document when attributed. **No `price`, no `seller`, no `channel`.**
- **Shape L (legacy offer observation)** — `OffersService.upsertOffer`: `product_id`, `source`, `seller`, `channel`, `price` **int** (unit "legacy-unknown", `PricingService:28-29`), `ts` Date.
- **Added by rollup:** `rolled: true`.
- **Neither shape carries a type discriminator.** Shape is inferred by field presence. This is the root of the R1 hazard.
- **Validator: DEFER** until a discriminator exists (§9.4). A validator over undiscriminated shapes would be a `oneOf` on field presence, which perpetuates the ambiguity.

### 6.8 Other critical / identity-sensitive collections

| Collection | Contract summary | Strictness fallbacks (listed) | Validator |
|---|---|---|---|
| `customers` | `_id` `CUS_*`; `phoneNormalized` string `+91[6-9]xxxxxxxxx` (unique `customer_one_per_phone`); `status` literal `"ACTIVE"` (no enum, **no reader checks it**); `createdAt` (set on insert), `updatedAt`, `lastLoginAt` Date. PII (phone). Written by upsert in the session-establish Tx. | existence-only reads; any other defect invisible (C) | **DEFER** — contract is minimal and easy, but live data and PII handling are UNVERIFIED; and `status` has no defined state machine |
| `customer_addresses` | `_id` `ADDR_*`; `customerId`; `label ∈ HOME/WORK/OTHER`; `recipientName ≤80`, `recipientPhone`, `addressLine1 ≤160`, `addressLine2|null`, `landmark|null`, `city`, `state`, `postalCode`; `latitude|longitude` double|null (both-or-neither); `version` long; `createdAt`,`updatedAt` Date. `isDefault` not stored. PII. | bad/missing label ⇒ 503; missing `version` ⇒ NPE; optional strings ⇒ null (C) | **DEFER** (PII, live data UNVERIFIED) |
| `customer_address_state` | `_id` = customerId; `addressCount` long (guarded `$lt limit`); `defaultAddressId` string|null (explicit null possible); `updatedAt`. **`decrement` has no floor** (negative on corruption); no reconciliation job. | missing `addressCount` ⇒ NPE or treated as limit (C) | **DEFER** — resolve the floor/reconciliation contract first |
| `customer_profiles` | `_id` = customerId; `displayName` string|null (≤80, blank→null), `email` string|null (lower-cased, ≤254); `createdAt` Date (`$setOnInsert`), `updatedAt`, `version` long (`0` ⇒ upsert). PII. | missing strings ⇒ null; missing `version` ⇒ NPE ⇒ 503 and breaks PATCH (C) | **DEFER** |
| `customer_carts` | `_id` = customerId; `items[{skuId,quantity int,addedAt,updatedAt}]`, `version` long, `createdAt`,`updatedAt`,`expiresAt` (= updated + 7d); optional `purchasedThroughVersion` long (absent = 0, written only by purchase finalization via `$max`). Never deleted; no TTL by design. | missing `expiresAt` ⇒ not expired; missing `items` ⇒ empty; quantity ≤0 **not rejected on read**; marker present non-Number/negative ⇒ `CartPurchaseIntegrityException`; explicit-null marker ⇒ absent = 0 (C; marker A) | **DEFER** — `quantity ≥ 1` and `version` are good candidates, but expiry/housekeeping semantics must be settled first |
| `customer_sessions` | `_id` `SES_*`; `customerId`; `createdAt`,`expiresAt` Date; `revokedAt` Date|null (null at create); `refreshTokenDigest` base64 HMAC (**sensitive**); `refreshGeneration` int; `lastRotatedAt`. TTL `session_expiry_ttl` (cleanup only; app-level expiry authoritative). | `revokedAt:null` also matches missing; non-date `expiresAt` ⇒ inactive; missing digest ⇒ NPE ⇒ 500 (C, fail-closed) | **NO** — ephemeral, TTL-managed, auth-critical write latency; correctness is by CAS filters |
| `customer_otp_challenges` | `_id` `OTP_*`; `phoneNormalized`,`purpose=LOGIN`, `otpVerifier` (**sensitive** HMAC), `createdAt`,`deliveryDeadline`, nullable `expiresAt`/`resendAvailableAt`/`verifiedAt`/`grantId`/`lastSentAt` (explicit null at insert), `attemptCount` int, `maxAttempts` int, `status` (7 values), marker booleans `delivering`/`active` (partial-unique). Two TTL indexes. | **missing `maxAttempts` ⇒ `Integer.MAX_VALUE` (unlimited attempts)**; missing `attemptCount` ⇒ 0; bad `status`/dates ⇒ raw exception ⇒ 500 (D/C) | **NO** — D; but the `maxAttempts` default is a recorded **risk** (DB-0 R8) |
| `customer_otp_verified_grants` | `_id` `GRANT_*`; `challengeId` (unique), `phoneNormalized`, `purpose`, `createdAt`,`expiresAt`, `consumedAt` null|Date. TTL. | missing `consumedAt` matches `== null` ⇒ consumable (D) | **NO** |
| `domain_events` | `{aggregate_type, aggregate_id, type, detail, at, actor?}`; strings non-blank ≤200; `actor` `{type ∈ HUMAN_ADMIN/SERVICE_ACCOUNT/SYSTEM, id, credential_id?, request_id?}` exact key-set; insert-only. No reader in main. | absent `actor` ⇒ Optional.empty (historical/unattributed, never guessed); present must be exact — but **no reader in main**, so this strictness is never exercised (C) | **DEFER** — good candidate once a reader/audit-read contract exists (none in main today) |

Remaining collections are summarised in §7.

---

## 7. Contracts — remaining collections

### 7.1 `products` (existing validator) — see also §8

- `_id` string `^TZP-`; business identity: `identity.internal_key` (`identity_keys._id`), GTINs (`gtin_registry._id`), `identity.canonical_key` (`canonical_keys._id`).
- Required: `_id, product_type, identity, brand_code, title, lifecycle, classification, attributes, attributes_meta, version, created_at` (`SB:390-455`).
- Enums: `product_type` {single, variant_pack, bundle}; `lifecycle` {draft, active, merging, discontinued, archived, merged}; `classification.status` {confirmed, provisional, review, scope_blocked}; `identity.type` {gtin, internal}.
- `version` Int32 (CAS in `WritePath.casUpdateWithEvent`: filter `{_id, version:(int)expected}`; every writer must `$inc version`). Immutable fields guarded app-side: `_id`, `product_type`, `identity` (`assertNoImmutableFieldWrites`), except write-once `identity.canonical_key(_version)` via `setCanonicalKeyOnce`.
- Legacy: `identity.canonical_key` may be absent on pre-CAT-ID products (backfilled write-once).
- **Read-path silent defaults (C exception):** `CatalogCardReader` missing `version` ⇒ 0, missing classification ⇒ null vertical; `ConsumerEligibility` fail-closed (not eligible); `ProductController.toResponse` stringifies nulls as the literal `"null"` (`:176-199`).
- Unknown fields: **rejected** by validator (`additionalProperties:false`).
- Retention: none. Patterns: PAG-2 compound index list reads with `_id` cursor; sparse indexes on `bundle_contents.component_product_id`, `variant_group_id`.

### 7.2 Catalogue identity and evidence

| Collection | Contract | Silent fallbacks / notes |
|---|---|---|
| `gtin_registry` | `_id` GTIN; `bindings:[{product_id, market, from, to}]`; closed by arrayFilters, appended by `$push` upsert | no version, no uniqueness across products beyond `_id`; no reader except updates |
| `identity_keys` | `_id` internal key; `{product_id, status ∈ active|redirected, redirected_to?}` | no reader |
| `canonical_keys` | `_id` key; `{product_id, version, status:"active", created_at}`; E11000 ⇒ `IdentityCollisionException` | reader `ProductQueryService.findByCanonicalKey` |
| `evidence` | `_id` `EV-*` (idempotency key); `evidence_type` ∈ pdp/ingredients/lab_report/supplier_doc/marketplace_path/human/title; `validity` ∈ active/retracted/superseded; `payload_state` (only `readable` is ever written); `fence` int (`$inc`, serialises validity flips vs publish) | identical replay ⇒ EXISTING; differing replay ⇒ `EvidenceImmutableException`; unknown fields accepted |
| `evidence_links` | `{evidence_id, product_id, link_type ∈ classification|claim, active (only ever set true), attribute_key?}` | upserted; read by taint worker |
| `classification_history` | `{product_id, vertical_id, release_id, status, method ∈ mint|rule, decided_at, confidence? (classify only)}` insert-only | **no actor in the row** (actor only on the paired `product_events` row); no reader |
| `product_events` | `{type, product_id, detail, at, actor?}`; one row per `WritePath` write (N per logical op); `product_id` is overloaded: `TZP-SYSTEM` (taxonomy/attribute/evidence/taint/stamp/rollup), SKU (pricing/inventory/media), verticalId (`CK_BACKFILL_*`) | no reader, no sequence, ordered by `at` only; unattributed for inventory, pricing w/o actor, media, rollup, CK backfill |
| `offers_current` | `(product_id, source, seller, channel)` unique; `price` int (legacy unit), `available` bool, `last_seen_at` | no reader except merge; **no main caller of `upsertOffer`** |
| `work_queue` | deterministic string `_id`s per item type; lease fields `status ∈ pending|leased|completed`, `lease_owner`, `lease_until`; `checkpoint` (ObjectId for taint, string for stamp/backfill); card-rebuild rows add `request_generation`, `attempt_count`, `lease_token` | **D**; completed rows of every type except `card_rebuild` are never purged |

### 7.3 Taxonomy, attributes, reference

| Collection | Contract | Notes |
|---|---|---|
| `taxonomy_nodes` | `_id` `TZS/TZC/TZB/TZV-*`; `node_type`, `name`, `parent_id`, `status ∈ active|deprecated|merged`, `attribute_schema_id`, `created_in_version`, `version` Int32 (CAS `casNode`); optional `merged_into`, `origin`, `branch_status` | sibling-name uniqueness is by `countDocuments`, not an index |
| `taxonomy_snapshot_nodes` | node copy minus `_id` plus `release_id`,`node_id`; `$setOnInsert` upsert (first write wins; re-run never refreshes) | reader fails closed on cycle/missing `node_id` (`SnapshotTopologyException`); per-release |
| `catalogue_releases` | `_id` releaseId; `status ∈ publishing|freezing|active`; `gate:"OPEN"` (partial unique; unset on activation); `based_on`, `opened_at`, `activated_at`, `change_seq` | **no baseline release on a fresh DB**; only `gate` is unique so "exactly one historical active release" is not enforced |
| `system_config` | only `consumer_taxonomy_release {release_id, updated_at}` is written in main; a `languages` row is read by `ValidatorGenerator` but never written | |
| `aliases` | `(alias_norm, lang, region)` unique; `node_id`, `status` | `node_id` filter has no index |
| `id_sequences` | `_id:"TZV"`, `seq` long from 100000; lazy-created; init swallows E11000 inside a Tx (unproven) | D |
| `attribute_definitions` | `(key, version)` unique; `type` ∈ string/number/boolean/enum_open; `governance` ∈ descriptive/claim/merchandising (merchandising refused at authoring); `status` ∈ pending/active/superseded; `known_values[]`; absent `status` ⇒ active (legacy) | write-side `valueMatchesType` accepts unknown type; consumer read fails closed |
| `attribute_schemas` | `(schema_id, version)` unique; `fields[{key, required}]`; `compat_breaking`; `status`, `release_id` | **mutated by `TaxonomyLoader` `updateMany` at every start** (R3) |
| `discriminating_attributes` | `{status:"active", vertical_id, version, discriminators[], vocabularies{…}}`; read once at startup; no writer, no seed | empty discriminators ⇒ startup abort |
| `consumer_projection_policy` | `vertical_id` unique; `projection_version`; `attributes[{attribute_key, display_order, display_label}]`; **strict parser** (`ConsumerProjectionPolicy.from`), 503 on any defect | read-only in main |
| `attachment_registry` | read only by `ValidatorGenerator`; no writer | |
| `node_events` | `{node_id, event ∈ renamed|re_parented|merged|split|deprecated|revived, release_id, detail, at, actor?}` | no reader |

### 7.4 Pricing/inventory/media/serviceability/projection

| Collection | Contract | Silent fallbacks |
|---|---|---|
| `price_rollups` | `(product_id, seller)` unique; `min_price`,`max_price`,`count` (legacy int unit) | write-only, no reader; paise rows would key `{product_id=sku, seller=null}` (DB-0 §13) |
| `media_refs` | `(owner_type ∈ PRODUCT|SKU, owner_id)` unique; `version` long; `assets[{asset_id, asset_key, role ∈ PRIMARY|GALLERY, sort_order, alt_text, width, height, content_type ∈ image/jpeg|png|webp}]` (≤50; unique ids/keys/sort_order; one PRIMARY at 0); `active`; whole-set replacement | unknown role ⇒ exception; `width`/`height` are nullable `Integer` (`MediaAsset:28-29`), written as explicit null when absent, read via `getInteger` so a stored Long ⇒ `ClassCastException`; missing `assets` ⇒ empty; missing `active` ⇒ false |
| `service_areas` | `pincode` unique; `service_area_id` (non-unique label); `routes[{fulfillment_location_id, priority, active}]` (≤20; unique location and priority); `version` long; `active` | missing `priority`/`version` ⇒ NPE; missing `active` ⇒ false; missing `routes` ⇒ empty |
| `product_card_base` | `sku_id` unique; `price_status` enum name; `selling_price_paise`,`mrp_paise` set only when price ACTIVE; `source_versions{catalog_version, price_version, media_version}`; `projection_version` long CAS | **C**: fail-fast only for `source_versions`, `catalog_version`, `projection_version`, `price_status`; a missing `price_version`/`media_version` or other nullable fields read as null silently (`ProductCardProjectionService:302-318`). Disposable/rebuildable; rebuild is flag-gated (off by default) |

### 7.5 Unused collections

`brands`, `variant_groups`, `marketplace_crosswalks`, `batches`, `campaigns`, `campaign_membership`, `rollup_state` are created by `SchemaBootstrap` and referenced by no reader/writer in main (`batches` and `campaign_membership` carry unique indexes). No contract can be derived from code. Policy: **no validator, no contract**; their retention or retirement is a DB-2 decision (an existing empty collection is harmless; dropping is a destructive migration under R5).

---

## 8. `products` validator — exact current behaviour (VERIFIED)

- **Definition:** `SchemaBootstrap.productsSchema()` (`SB:390-455`): `$jsonSchema` with `additionalProperties:false`; required `_id, product_type, identity, brand_code, title, lifecycle, classification, attributes, attributes_meta, version, created_at`; `_id` pattern `^TZP-`; enums for `product_type`, `lifecycle`, `identity.type`, `classification.status`; `classification.confidence` `["double","null"]` 0..1; `gtins` maxItems 12; `classification.evidence_refs` maxItems 20 (`^EV-`); `bundle_contents` maxItems 100 (`qty` int ≥1); `pack_of.qty` ≥2; `browse_verticals` maxItems 120; `version` int ≥1; `ext` `additionalProperties:false` (empty by default); `oneOf` by `product_type` (single ⇒ `vertical_id` string and null `bundle_contents`/`pack_of`; variant_pack ⇒ `pack_of`; bundle ⇒ null `vertical_id`, `bundle_contents` `minItems 2`). `supersedes` is mentioned in `ProductLifecycleService` comments but is **not** in the schema and would be rejected.
- **validationLevel / validationAction:** `STRICT` / `ERROR` (`SB:156-157`).
- **Create-time behaviour:** applied only when `bootstrap()` finds `products` absent in `listCollectionNames()` (`SB:152-158`) and creates it with `CreateCollectionOptions().validationOptions(...)`.
- **When the collection already exists:** `bootstrap()` does nothing to its validator. It neither applies, refreshes, nor verifies. A `products` collection that pre-exists without a validator, with a stale validator, or that was created implicitly by an earlier insert, **permanently has whatever it has** — there is no drift detection.
- **`ValidatorGenerator.regenerate()`:** `J/catalog/schema/ValidatorGenerator.java:44-49` issues `collMod` on `products` with `validationLevel strict`, `validationAction error`, rebuilding the schema so `localized_titles` keys come from `system_config {config_type:"languages"}.values` (default `[en,hi]`) and `ext` keys from `attachment_registry {target:"products.ext"}`.
- **Does production call it?** **No.** It has **no caller in `src/main`**; only `ValidatorRegenIT` calls it (verified by grep in DB-0).
- **Deployment implications:**
  1. A fresh environment gets the validator only if `bootstrap-on-startup` runs against an empty database first (it does by default, `application.yml:14`).
  2. Any environment where `products` existed first has **no** guaranteed validator (live state UNVERIFIED).
  3. Registering a new language or `ext` attachment key changes the intended schema, but production has no mechanism to apply it; `ValidatorGenerator` would have to be run by a controlled migration (R5), not at startup.
  4. `STRICT` means any update to a pre-existing non-conforming `products` document fails; a stale or too-tight validator therefore can block catalogue writes.
  5. The validator is the **only** server-side contract in the database; the service tier also enforces the rules the validator cannot (`ValidatorContractIT` proves a "bypass fake attribute key" is accepted by Mongo and must be rejected by the service).
- **Policy:** keep `products` validator semantics as is; move validator application/refresh into the controlled migration (R5), including a **verification** step (`listCollections` options check) that is safe at runtime. Decision on whether `STRICT` should become `moderate` is DEFERRED to DB-3 with live-data evidence.

---

## 9. R1 contract — immutable vs purgeable price-event categories

### 9.1 Facts (VERIFIED; see DB-0 §13)

`CatalogSchedulers.priceRollup()` (hourly, default on) calls `RollupService.rollup()` then `purge()`. `rollup` selects every `price_events` row with `ts ≤ now AND rolled != true` with **no shape/type filter**, aggregates `price`/`seller` into `price_rollups`, marks `rolled:true`; `purge` runs `deleteMany({rolled:true})`. Paise-ledger rows (Shape P) satisfy the filter. Nothing in main currently writes Shape P, so the hazard is latent. Three Javadocs claim the history is immutable.

**Current behaviour is NOT compliant with R1.** The hourly job deletes every rolled `price_events` row **by default** (`CatalogSchedulers:88-94`, `application.yml:17`, `RollupService:64-70`). This applies to legacy Shape L rows that may exist live today (not only the latent Shape P hazard), and existing tests pin the purge as intended (`RollupStallIT`, `SchedulerIT:176`; DB-0 §13). The statements below describe the **required target contract**, not current behaviour; moving to it needs the later work package and test changes.

### 9.2 Categories

| Category | Definition (today) | Class | Rollup eligible | Purge eligible |
|---|---|---|---|---|
| **P — paise price-change ledger** | Shape P rows: `sku_id` + `selling_price_paise` + `mrp_paise` + `version` + `effective_*` + `source` + `ts` (+ optional `actor`) | **IMMUTABLE durable commercial/audit history** | **NO** | **NEVER** |
| **L — legacy offer observation** | Shape L rows: `source`, `seller`, `channel`, `price` int in an unknown unit | **UNPROVEN** | not by default | **NOT by default.** Retained until an explicit eligibility proof exists (§9.3) |
| **U — unknown / any other shape** | rows matching neither P nor L, including any future shape | **RETAINED** | NO | **NEVER by default** |
| **R — rolled flag** | `rolled:true` on a row | a *marker*, not a category; it must not by itself authorise deletion | — | no (the marker alone is insufficient) |

### 9.3 What "explicitly proven eligible" must mean (contract for DB-2/DB-3)

A type becomes purge-eligible only when **all** hold: (1) rows carry an **explicit durable discriminator** written by code (e.g. an event-kind field), not inferred by field absence; (2) the owner confirms in writing that raw rows of that kind are not durable commercial/audit history; (3) the aggregate that survives is sufficient for every documented consumer; (4) the purge filter names the discriminator **and** `rolled:true`; (5) tests prove Category P and U rows survive a rollup+purge cycle.

Until (1)–(5) are met, the **required** posture is that no `price_events` row is deleted (target; currently violated, see above). The conservative production posture is rollup/purge disabled or restricted to nothing. Because no purge-eligible type is currently proven, the recommended interim contract is "retain everything".

### 9.4 Contract requirements for the later work package

- Introduce an explicit discriminator for new rows; classify existing rows by shape **only** for read, never to authorise deletion; legacy Shape L rows without a discriminator stay retained.
- Rollup must not read or mark Category P/U rows; paise rows must never be keyed `seller=null` into `price_rollups`.
- Remove the dead `rollup-lag-seconds` config or implement it (DB-0 R2).
- Retention tiers (hot/cold/archive) for the retained ledger are a DB-6 decision, not a deletion.
- `price_events` validator: DEFER until the discriminator exists (§6.7).

This is a **contract/ruling record only**; the implementation belongs to a later DB work package (suggested: DB-2 for discriminator/index, DB-3 for the migration that stops purge, DB-6 for retention tiers). DB-1 changes no code.

---

## 10. R3 contract — taxonomy/attribute reference data as versioned durable state

- **Principle:** `attribute_definitions`, `attribute_schemas`, `taxonomy_nodes`, `aliases`, `taxonomy_snapshot_nodes`, `catalogue_releases` and `system_config` are **versioned durable state**. Their documents are not regenerated or overwritten by application restarts.
- **Allowed at startup:** insert-if-absent initial reference data (`$setOnInsert` upserts keyed on `(key,version)`, `(schema_id,version)`, `_id`, `(alias_norm,lang,region)`), exactly as `TaxonomyLoader` does for its four upserts.
- **Not allowed as the target contract:** an unconditional `updateMany` that rewrites persisted attribute schemas on every start. The current `TaxonomyLoader:75-80` (`fields.$[q].required=false` for `pack_size`/`pack_unit`, all versions, every start) is a **recorded deviation** (DB-0 R3), contradicts "never clobbers" (`TaxonomyLoader:27`) and "version documents are immutable" (`AttributeAuthoringService:28-29`), and is not the desired future contract. It does not hold the version-immutability invariant.
- **Evolution:** any change to existing reference contracts is a **named, versioned, repeatable migration** (§11): carries an id, preconditions, a dry-run/measure step, an idempotent apply, a recorded outcome, and never silently touches a version document that is `active` or `superseded` — a changed contract is a **new version** (consistent with the existing release workflow: `AttributeAuthoringService`/`TaxonomyChangeService` write new pending versions and activate them at release).
- **Seed source:** the v0.9.0 seed (`taxonomy_v0_9_0_seed.json`: 460 nodes, 25 aliases, 110 definitions, 48 schemas) is the initial reference set; its later required-ness change is expressed as a **new schema version created by a versioned migration**, or — only if the owner explicitly approves it — a **one-time, recorded correction** of the already-`active` seed versions (the loader inserts them with `status:"active"`, `TaxonomyLoader:53-58`, so any in-place edit rewrites an active version). An in-place rewrite is never the default mechanism and never a restart-time loop.
- **Fresh-DB baseline:** `catalogue_releases` and the `consumer_taxonomy_release` pointer have no baseline path in code today (`recordBaseline` has no caller). The initialization path (who seeds, when) is a DB-5 reference-data decision under the same versioned-migration contract.
- **Validators:** DEFER for taxonomy/attribute collections until the versioned migration mechanism exists (otherwise a validator would freeze the very fields R3 says must evolve through migrations).

---

## 11. R5 contract — runtime bootstrap vs migration vs reference initialization

Three distinct kinds of startup activity; DB-0 §9 shows today's `bootstrap()` mixes all three on every instance.

| Kind | Examples (today) | Contract (target) |
|---|---|---|
| **A. Safe runtime verification** — read-only or idempotent no-op checks that are safe to run on every instance concurrently | `listCollectionNames`, `listIndexes` comparison, validator-option check, readiness ping, "does the expected schema version marker match" | **Allowed** in the application at startup. Must fail readiness (or log loudly) on mismatch rather than mutate. Must not require write privileges. |
| **B. Migration-time mutation** — creating collections, creating/dropping indexes, `collMod`, any data rewrite | `createCollection`, all `createIndex`, `dropHistoricalPlainPrefixIndex` (`dropIndex`), `ValidatorGenerator` `collMod`, `TaxonomyLoader.updateMany` | **Not** run blindly by every app instance. Moves to a controlled process: **explicit** (named steps), **versioned** (id + applied-ledger), **idempotent**, **observable** (logged/metered outcome), **safe to retry**, **separately permissioned** (a migration identity distinct from the runtime identity; the runtime user need not hold `createIndex`/`dropIndex`/`collMod`), and guarded by a **single-runner lock** so destructive steps never race across instances. |
| **C. Reference-data initialization** — insert-if-absent seeding | `TaxonomyLoader` four `$setOnInsert` upserts | Allowed as an explicit, versioned migration step (or one-time job) using insert-if-absent; not re-run mutating on each start. |

Specific implications (recorded; implementation is DB-3):
- The conditional `dropIndex` on `products` (`SB:168,350-388`) is a Kind-B destructive step: must be an explicit migration step, document its privilege, keep the existing safety (create the superseding index first; exact-match whitelist) and be recorded as applied.
- `createCollection` and `createIndex` option conflicts are currently uncaught and abort startup; in a migration they must be handled and reported (NamespaceExists benign; IndexOptionsConflict/KeySpecsConflict surfaced as migration failure with an operator decision, never auto-dropped).
- Until then, `application.yml` hard-codes `bootstrap-on-startup: true` and `load-taxonomy-seed: true`. The target is that **production runs with Kind-B disabled and Kind-A enabled**, with Kind-B executed by the migration job; flag defaults must not silently re-enable it.
- Observability (target): a persisted migrations ledger (collection name to be decided in DB-3; **no new collection is created by DB-1**) and metrics/log lines per step.

DB-1 does **not** redesign bootstrap. It records this as the contract constraint on DB-2 (indexes), DB-3 (migration framework) and DB-4 (separate privileged users).

---

## 12. Validator rollout preconditions and migration implications

### 12.1 Preconditions before **any** validator is implemented (all must hold)

1. A known legacy shape inventory for the collection (§6) — present for the six YES collections.
2. Evidence of existing live data conformance: a measured conformance scan in each persistent environment (`find` with the negated `$jsonSchema`) — **UNVERIFIED today** (no live access in DB-0/DB-1).
3. Deterministic apply/refresh behaviour: validator application belongs to the controlled migration (R5), not to restart-time bootstrap; drift is verified (Kind A).
4. A migration strategy (id, dry-run, apply, rollback = restore previous validator options; note `collMod` to remove/relax is itself safe and reversible).
5. Tests: every production write path against the validator in a Testcontainers run (including retry paths), plus a legacy-document test, plus a bootstrap-idempotency test.
6. No production ambiguity: gates 1–6 (DB-0 §19) resolved for the affected collections.

These conditions are **not** satisfied for any new validator today, so **no validator is implemented in DB-1.**

### 12.2 Recommended staged rollout (for DB-3/DB-7)

`collMod` order: `validationAction: warn` + `moderate` in staging → review logs → `error`/`moderate` → optional `strict`. Never combine a validator rollout with an index or data migration in one step. Start with the lowest-risk collections (`inventory_reservations` and `price_current` — both are inserted whole and then only CAS-updated, not insert-only; `price_current` before any production caller exists), then `checkout_quotes`/`orders`/`memberships` after gates 1–2.

### 12.3 Migration implications (summary)

| Item | Implication | Phase |
|---|---|---|
| Validator YES collections (6) | each needs a conformance scan + controlled `collMod`; none needs a data rewrite; **no backfill of legacy `benefits`/`money` is permitted** (historical facts are not synthesized) | DB-3 / DB-4 |
| `products` validator | add apply/refresh/verify to the migration; decide `strict` vs `moderate`; regenerate on language/`ext` changes | DB-3 |
| R1 `price_events` | discriminator + stop-purge migration; retention tiers | DB-2/3/6 |
| R3 `TaxonomyLoader` | remove restart-time `updateMany`; express as versioned migration | DB-3/5 |
| R5 bootstrap | split A/B/C; single-runner lock; separate users | DB-3/4 |
| Unused collections / unused indexes | retire or document; any `dropCollection`/`dropIndex` is an explicit destructive migration | DB-2 |
| OTP `maxAttempts` default | not a validator matter; record as code-level risk | out of DB-1 |
| `customer_address_state.decrement` floor | contract decision before any validator | DB-1 follow-up |

---

## 13. Live-environment unknowns that bound DB-1

UNVERIFIED (need Atlas/live evidence): whether `orders`, `memberships` or any collection exists with legacy/conflicting shape in any persistent environment; actual validator state of a live `products` collection; actual index names and presence; effective app user privileges (`collMod`, `dropIndex`); deployed URI/read-write concern; live row counts; whether pre-PR-13B quotes (no `addressVersion`) exist; BSON type of `Instant`-written fields; BSON type of `inventory.max_purchasable`/`low_stock_threshold`. DB-1 policy is written so that none of these is assumed.

## 14. Open decisions for the database owner

1. Confirm the six PROPOSED YES validators and their `moderate`/`error` posture as the planned end state (implementation still gated by §12.1).
2. Confirm "retain everything in `price_events`" as the interim R1 posture until a discriminator exists.
3. Decide whether `products` should stay `STRICT` or become `moderate` once the migration mechanism exists.
4. Decide the fate of the seven unused collections (retire vs keep).

## 15. Next DB task

**DB-2 — Indexes + uniqueness + TTL.** Name all indexes explicitly; lock the DB-0 §8 manifest; add the missing assertions (TTL ×4, unasserted unique/partial indexes); decide unused/unreferenced indexes; introduce the R1 price-event discriminator design; stage the stop-purge change.

---

*DB-1 changed no application code, configuration, schema, validator, index or data, and made no connection to MongoDB.*
