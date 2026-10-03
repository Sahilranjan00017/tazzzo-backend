# DATABASE_INVENTORY — tazzzo-backend (DB-0, read-only discovery)

## 1. Audit metadata

| Item | Value |
|---|---|
| Repository | `Sahilranjan00017/tazzzo-backend` (`https://github.com/Sahilranjan00017/tazzzo-backend.git`) |
| Authoritative SHA | `f5b2cdd50d405e2554dfa0a9772b07c8569b9c2b` (`origin/main`, "Add admin me endpoint for CMS bootstrap (#46)") |
| Source tree used | clean checkout of `origin/main` at `f5b2cdd` (`rev-list --left-right --count HEAD...origin/main` = `0 0`) |
| Fetch | `git fetch --all --prune` run at audit start |
| Service audited | `services/catalog-service` — the only Maven module with persistence |
| Open PRs | none at audit time |
| Audit date | 2026-10-03 |
| Method | static source inspection only. No build, no test run, no Mongo connection, no data/config mutation, no application-code change |
| Scope | Describes committed `f5b2cdd` only. Persistence changes merged after this SHA must be re-inventoried. |

Path convention: `J` = `services/catalog-service/src/main/java/com/tazzzo`, `R` = `services/catalog-service/src/main/resources`, `T` = `services/catalog-service/src/test/java/com/tazzzo`. `SB` = `J/catalog/schema/SchemaBootstrap.java`. `ESD` = `docs/ENGINEERING_STATUS.md`.

Status vocabulary: **VERIFIED** (read from source), **UNVERIFIED** (needs live evidence or not provable from repo), **CONFLICT — INVESTIGATION REQUIRED**.

Evidence note: findings come from three parallel read-only audits; the highest-impact claims (§20 R1–R5) and the cited `SchemaBootstrap` index/TTL lines were re-checked directly against `f5b2cdd`. Other `path:line` cites are audit-derived and should be re-confirmed by the phase that acts on them. "VERIFIED" in tables means read from source at `f5b2cdd` by one of these passes.

---

## 2. Mongo topology assumptions

| Fact | Evidence | Status |
|---|---|---|
| Raw sync driver, `MongoDatabase`/`MongoCollection<Document>`. No Spring Data repositories, no `MongoTemplate`, no `MongoTransactionManager`, no POJO codecs | `J/catalog/CatalogApplication.java:33-36`; comments at `J/auth/otp/OtpChallengeRepository.java:20`, `J/auth/session/CustomerSessionRepository.java:20` | VERIFIED |
| `spring-boot-starter-data-mongodb` (Boot 3.3.5) supplies only the auto-configured `MongoClient`; driver version not pinned | `pom.xml:8,23` | VERIFIED; resolved driver version UNVERIFIED |
| URI default `mongodb://localhost:27017/tazzzo?replicaSet=rs0`, override `MONGODB_URI`; DB `${MONGODB_DATABASE:tazzzo}` | `R/application.yml:10-11` | VERIFIED |
| Replica set required (all writes use `ClientSession.withTransaction`) | `J/catalog/tx/Tx.java:28-48` | VERIFIED |
| Server target MongoDB 7 (local compose `mongo:7`, Testcontainers `mongo:7`, floating tag) | `docker-compose.local.yml:19`; `T/catalog/AbstractMongoIT.java:18-26` | VERIFIED (repo); production server version UNVERIFIED |
| **No** `readPreference`, `readConcern`, `writeConcern`, `retryWrites/Reads`, `maxCommitTime`, `TransactionOptions`, pool or timeout settings anywhere in main | greps over `src/main`; only per-handle setting is `J/membership/MembershipRepository.java:64` (`ReadPreference.primary()`) | VERIFIED. Effective values = driver defaults + whatever `MONGODB_URI` carries at deploy = UNVERIFIED |
| Redis is used for OTP rate limiting only, not persistence | `R/application.yml`; `J/auth/otp/OtpRateLimiter.java` | VERIFIED |
| Local compose: single-member `rs0`, **no auth** | `docker-compose.local.yml:19-43` | VERIFIED (local only) |
| Admin principals are not persisted; allowlist/tokens are config | `J/admin/auth/*` has no Mongo access | VERIFIED |
| Benefits rules and Membership plans are config, not persisted | `J/benefits/ConfigBackedBenefitRuleSource.java`; `R/application.yml:109-127` | VERIFIED |

---

## 3. Collection inventory

`SchemaBootstrap.COLLECTIONS` declares **49** collections (`SB:26-114`). Class: A authoritative / S snapshot / E event / O operational / D derived. Only `products` has a server-side validator (§7). Unless stated: no TTL, no validator, unknown top-level keys ignored on read.

### 3.1 Catalogue / products

| Collection | Owner | Class | `_id` | Business identity | Notes |
|---|---|---|---|---|---|
| `products` | `catalog.tx` via `WritePath` | A | caller-supplied string `^TZP-` | `identity.internal_key`, GTINs, `identity.canonical_key` | `version` Int32 CAS; validator; required: `_id, product_type, identity, brand_code, title, lifecycle, classification, attributes, attributes_meta, version, created_at`. Legacy: `identity.canonical_key` may be absent (pre CAT-ID), handled by backfill. |
| `gtin_registry` | `MintService`, `GtinBindService` | A | GTIN string | — | `{bindings:[{product_id, market, from, to}]}`; never read in main except updates |
| `identity_keys` | `MintService`, `MergeService` | A | internal key | — | `{product_id, status active|redirected, redirected_to?}`; no reader |
| `canonical_keys` | `MintService`, `VariantPackService`, `CanonicalKeyBackfillService` | A | derived key | — | `{product_id, version, status, created_at}`; reader `ProductQueryService.findByCanonicalKey` |
| `discriminating_attributes` | none (read-only) | A | — | — | read at startup by `DiscriminatingAttributeRegistry.load`; no writer, no seed. Malformed ratification aborts startup |
| `brands`, `variant_groups`, `marketplace_crosswalks`, `batches`, `campaigns`, `campaign_membership`, `rollup_state` | none | — | — | — | created by bootstrap, **no reader/writer in main**. `batches` and `campaign_membership` carry unique indexes |
| `attachment_registry` | none | A | — | — | read only by `ValidatorGenerator:35`; no writer |
| `work_queue` | many | O | deterministic strings (`merge:<l>:<s>`, `taint:<ev>`, `card_rebuild:<sku>`, `ck_backfill:<v>`, …); `classification_review` rows use ObjectId | — | lease fields `status, lease_owner, lease_until`, `checkpoint`; `card_rebuild` rows have `request_generation, attempt_count, lease_token`. Only `card_rebuild` rows are ever deleted (`J/commerce/read/ProjectionRebuildWorker.java:113`). Completed rows of other types are never purged |
| `evidence`, `evidence_links` | `EvidenceService`, `TaintService`, `PublishService`, `ClassifyService` | A | `EV-*` caller-supplied (idempotency key) / ObjectId | — | `fence` int `$inc`; `validity active|retracted|superseded`; `evidence_links.active` only ever set true |
| `offers_current` | `OffersService`, `MergeService` | A | ObjectId | `(product_id, source, seller, channel)` unique | legacy-unit `price` int; no reader except merge |

### 3.2 Taxonomy and attributes

| Collection | Class | `_id` | Notes |
|---|---|---|---|
| `taxonomy_nodes` | A | `TZS-/TZC-/TZB-/TZV-*`; new verticals `TZV-%06d` | `node_type super_category|category|sub_category|vertical`, `status active|deprecated|merged`, `version` int CAS (`casNode`). Seed 460 nodes. Sibling-name uniqueness is by `countDocuments`, not an index |
| `taxonomy_snapshot_nodes` | S | ObjectId (code never sets it) | copy of live node + `release_id`, `node_id`; `$setOnInsert` upsert on `(release_id,node_id)`; `SnapshotTaxonomyReader` fails closed on cycle/missing `node_id` (`SnapshotTopologyException`) |
| `catalogue_releases` | A | caller `releaseId` | `status publishing|freezing|active`, `gate:"OPEN"` (unset on activate), `based_on`, `change_seq` (`ReleaseGate` `$inc`). **No release exists on a fresh DB** |
| `system_config` | A | `consumer_taxonomy_release` is the only row written | `{release_id, updated_at}`; `{config_type:"languages"}` row is read by `ValidatorGenerator` but never written |
| `aliases` | A | ObjectId | `(alias_norm, lang, region)` unique; 25 seeded, all `lang:"xx"`, `region:"all"` |
| `id_sequences` | O | `"TZV"` | `seq` long, lazy-created at 100000 (`J/catalog/tx/TaxonomyChangeService.java:515-523`) |
| `attribute_definitions` | A | ObjectId | natural key `(key, version)`; `type string|number|boolean|enum_open`, `governance descriptive|claim|merchandising`, `status pending|active|superseded`, `known_values[]`. Legacy: absent `status` treated as active (`AttributeAuthoringService:226-228`) |
| `attribute_schemas` | A | ObjectId | natural key `(schema_id, version)`; `fields[{key,required}]`, `compat_breaking` |
| `consumer_projection_policy` | A | not set | `vertical_id` unique; read-only in main; **strict** parser `ConsumerProjectionPolicy.from` (J/catalog/consumer:44-141), 503 on defect |

No separate attribute-value / schema-field / evidence-relationship collections exist: values live in `products.attributes`, enum values in `attribute_definitions.known_values[]`, fields embedded in `attribute_schemas.fields[]`.

### 3.3 Pricing, inventory, media, serviceability, projection

| Collection | Class | `_id` | Money / key facts | Malformed-read behaviour |
|---|---|---|---|---|
| `price_current` | A | ObjectId; key `(sku_id, currency)` unique | `selling_price_paise`, `mrp_paise` int64; `currency:"INR"`; `version` int64 CAS; max 1,000,000,000; `mrp ≥ selling` | missing paise/version → NPE; missing `active` → false; invalid `Price` → IAE |
| `price_events` | E (ledger) | ObjectId | **two row shapes**: legacy `{product_id,source,seller,channel,price:int,ts}`; paise `{product_id=sku, sku_id,currency,selling_price_paise,mrp_paise,version,effective_from,effective_to,source,ts,actor?}`. Rollup adds `rolled:true` | **rolled up and hard-deleted hourly** — see §13, §20 R1 |
| `price_rollups` | D | ObjectId; `(product_id, seller)` unique | `min_price,max_price,count` | write-only, no reader |
| `inventory` | A | ObjectId; `(sku_id, fulfillment_location_id)` unique | `on_hand`, `reserved`, `version` long; `reserved ≤ on_hand ≤ 1,000,000` | missing counters → NPE; invariant violations → IAE; `active` missing → false |
| `inventory_reservations` | A | opaque `InventoryReservationId` | `orderId` unique; `status RESERVED|RELEASED|CONSUMED`; `expiresAt`; `fingerprint`; CAS on `status`; never deleted, no TTL (`SB:99-103`) | fails loud, no fallback |
| `media_refs` | A | ObjectId; `(owner_type PRODUCT|SKU, owner_id)` unique | `assets[]`, `version` long | unknown role → exception; long `width` → ClassCastException; missing `assets` → empty list; missing `active` → false |
| `service_areas` | A | ObjectId; `pincode` unique | `routes[]`, `version` long | missing `priority`/`version` → NPE; missing `active` → false; missing `routes` → empty |
| `product_card_base` | D (rebuildable) | ObjectId; `sku_id` unique | `selling_price_paise`/`mrp_paise` set only when price ACTIVE; `projection_version` CAS | **fail-fast**, nothing defaulted (`ProductCardProjectionService.fromDocument:302-319`) |

### 3.4 Customer, auth, order, membership

| Collection | Class | `_id` | Key fields / identity | Sensitive |
|---|---|---|---|---|
| `customer_otp_challenges` | O | `OTP_*` | `phoneNormalized`, `purpose`(`LOGIN`), `status` (PENDING_DELIVERY, ACTIVE, VERIFIED, LOCKED, EXPIRED, SUPERSEDED, DELIVERY_FAILED), `delivering`/`active` marker booleans, `attemptCount`, `maxAttempts`; explicit nulls at insert for `expiresAt, resendAvailableAt, verifiedAt, grantId, lastSentAt`. No version; CAS by filter on `status` | phone (PII), `otpVerifier` (HMAC) |
| `customer_otp_verified_grants` | O | `GRANT_*` | `challengeId` unique, `consumedAt` | phone |
| `customers` | A | `CUS_*` | `phoneNormalized` unique; `status:"ACTIVE"` literal, **no reader checks it** | phone |
| `customer_sessions` | A | `SES_*` | `customerId`, `expiresAt`, `revokedAt`, `refreshTokenDigest`, `refreshGeneration` | `refreshTokenDigest` |
| `customer_profiles` | A | customerId | `displayName`, `email`(lower-cased), `version` long CAS (`0` → upsert) | PII |
| `customer_addresses` | A | `ADDR_*` | `customerId`, label `HOME|WORK|OTHER`, address fields, `latitude/longitude` double|null, `version` long. `isDefault` not stored | PII |
| `customer_address_state` | A (control) | customerId | `addressCount`, `defaultAddressId` (explicit null possible); limit guard via `$lt` upsert; `decrement` has no floor | — |
| `customer_carts` | A | customerId | `items[{skuId,quantity,addedAt,updatedAt}]`, `version`, `expiresAt`(+7d), optional `purchasedThroughVersion` (absent = 0). No TTL, never deleted | — |
| `checkout_quotes` | S | `CHKQ_*` | `idempotencyKeyDigest` (SHA-256 hex; raw key not stored), `fingerprint`, `cartVersion`, `addressId/Version`, `items[]`, `subtotalPaise`, `currency`, `expiresAt`, optional `benefits`, `money`. Immutable; never deleted | — |
| `orders` | A+S | `ORD_*` | `(customerId, quoteId)` unique; `status CREATED|CONFIRMED`, `paymentMethod COD`, `version` (1/2), `addressSnapshot`, `lines[]`, `subtotalPaise`, `currency`, `reservationId`, optional `benefits`, `money`, `confirmedPaymentCondition`/`confirmedAt` (CONFIRMED only). Immutable except the single CREATED→CONFIRMED shape | PII snapshot |
| `memberships` | A | `MBR_*` | `(grantSource,grantRef)` unique; `openTerm:true` only while ACTIVE (`$unset`, never false/null); `status ACTIVE|EXPIRED|REVOKED`; plan snapshot `planId, planVersion, planPricePaise, planCurrency, planPeriodMonths, billingZoneId, periodCount`; `cancelRequestedAt`, `revokedAt` absent-not-null | — |

### 3.5 Event / audit

| Collection | Class | Notes |
|---|---|---|
| `product_events` | E | see §12 |
| `classification_history` | E | see §12 |
| `node_events` | E | see §12 |
| `domain_events` | E | see §12 |

Cross-cutting representations: money is int64 paise (BSON Long) under `*Paise` / `*_paise` names; no Decimal128 or double money anywhere (VERIFIED). Currency is a plain string `"INR"`; `Currency` enum has only INR. Customer/membership collections use camelCase fields; catalogue/event collections use snake_case. OTP/grant/session/customer repos write `java.time.Instant` directly, the rest write `Date.from(...)`; read-back uses `getDate` (raw BSON type of the `Instant` writers UNVERIFIED by test).

---

## 4. Repository / adapter inventory

No Spring Data repositories exist. All access is `MongoDatabase.getCollection`.

- **Write rail:** `J/catalog/repo/WritePath` (every `insertWithEvent`, `casUpdateWithEvent`, `auxWrite`, `setCanonicalKeyOnce`), `ReleaseGate`, `ProjectionRebuildQueue`.
- **Tx runner:** `J/catalog/tx/Tx` (32 production classes inject it; `CommerceProjectionScheduler:58` builds its own `new Tx(client)`).
- **Catalog tx services:** `MintService, BundleService, VariantPackService, ClassifyService, PublishService, GtinBindService, ProductUpdateService, ProductLifecycleService, MergeService, TaintService, EvidenceService, OffersService, RollupService, AttributeAuthoringService, TaxonomyChangeService, CanonicalKeyBackfillService, ProductQueryService`.
- **Schema/seed:** `SchemaBootstrap, ValidatorGenerator, TaxonomyLoader, DiscriminatingAttributeRegistry, TaxonomyService, SnapshotTaxonomyReader, AttributeGovernanceService`.
- **Domain:** `pricing/PricingService`, `inventory/InventoryService`, `inventory/InventoryReservationService` + `InventoryReservationRepository` + `InventoryReservationExpiryWorker`, `media/MediaService`, `serviceability/ServiceabilityService`.
- **Commerce read:** `ProductCardProjectionService, ProductCardBaseReader, ProjectionRebuildWorker, ProjectionReconciler, CatalogCardReader, CommerceListService, CommerceSkuBatchReader`, session-aware ports `TransactionalCatalogCardReadPort, TransactionalPriceReadPort, TransactionalServiceabilityReadPort`.
- **Customer/auth:** `OtpChallengeRepository, OtpVerifiedGrantRepository, CustomerRepository, CustomerSessionRepository, CustomerProfileRepository, AddressRepository, CustomerAddressStateRepository, CartRepository, CheckoutQuoteRepository, OrderRepository, MembershipRepository`; adapters `CustomerIdentityAuthorityImpl, SessionAuthorityImpl, CartPurchaseService (CartPurchasePort), MembershipEntitlementReader/Service, TransactionalBenefitsEvaluationPort`.
- **Audit:** `common/audit/DomainAudit` (insert-only), `ActorDocuments` (codec).
- No controller holds a `MongoDatabase` (catalog/api, commerce/api, admin).

## 5. Reconstruction / codec inventory

| Codec | Location | Role |
|---|---|---|
| `OrderRepository.toOrder` (`:105-137`) | `J/customer/order` | strict + legacy-null for `benefits`/`money` |
| `CheckoutQuoteRepository.toQuote` (`:98-122`) | `J/customer/checkout` | strict + legacy-null for `benefits`/`money` |
| `MembershipRepository.toMembership` (`:226-245`) | `J/membership` | strict; any RuntimeException → INTEGRITY_FAILURE |
| `Checkout/OrderBenefitSnapshotCodec`, `Checkout/OrderMoneySnapshotCodec` | `J/customer/{checkout,order}` | exact key-set (`Set.equals`), Integer/Long only, payable re-derived |
| `ActorDocuments` (`:47-78`) | `J/common/audit` | absent = Optional.empty; present must be exact 4-key doc |
| `ConsumerProjectionPolicy.from` | `J/catalog/consumer` | strict, 503 |
| `ProductCardProjectionService.fromDocument` | `J/commerce/read` | fail-fast |
| `PricingService`, `InventoryService`, `MediaService.fromDocument` (`:228-246`), `ServiceabilityService.fromDocument` (`:243-258`), `InventoryReservationRepository.toReservation` | respective domains | constructor-invariant based; mixed NPE/IAE; some silent defaults (see §14) |
| `ProductDocuments.eventDoc` (`:79-89`) | `J/catalog/domain` | event row builder |

No POJO/Codec-registry mapping exists; `WritePath.java:131` uses `MongoClientSettings.getDefaultCodecRegistry()` only for an immutable-field check.

---

## 6. Startup flow (summary; detail §9)

`CatalogApplication.schemaBootstrapRunner` (`J/catalog/CatalogApplication.java:38-64`, `ApplicationRunner`): (1) if `bootstrap-on-startup` → `SchemaBootstrap.bootstrap`; (2) **always** `DiscriminatingAttributeRegistry.load`; (3) if `load-taxonomy-seed` → `TaxonomyLoader.load`. No try/catch, no lock, runs on every instance at every start. `R/application.yml:14-15` hard-codes both flags `true` (Java defaults are false, `CatalogApplication.java:43-44`).

## 7. Validator inventory

| Collection | Validator | Level/action | Applied when |
|---|---|---|---|
| `products` | `$jsonSchema` (`SB:390-455`) | STRICT / ERROR | **only at first creation** (`SB:152-158`) |
| other 48 | none | — | — |

`products` schema (VERIFIED `SB:390-455`): `additionalProperties:false`; required fields listed in §3.1; `_id` pattern `^TZP-`; `product_type` enum `single|variant_pack|bundle`; `lifecycle` enum `draft|active|merging|discontinued|archived|merged`; `identity.type` enum `gtin|internal`; `gtins` maxItems 12; `classification.status` enum `confirmed|provisional|review|scope_blocked`, `confidence` double|null 0..1, `evidence_refs` maxItems 20 pattern `^EV-`; `bundle_contents` maxItems 100 (qty int ≥1); `pack_of.qty` ≥2; `browse_verticals` maxItems 120; `version` int ≥1; `ext` `additionalProperties:false` (empty by default); `oneOf` by `product_type`. `supersedes` is mentioned in `ProductLifecycleService` comments but is **not** in the schema and would be rejected.

`ValidatorGenerator.regenerate` (`J/catalog/schema/ValidatorGenerator.java:44-49`, `collMod`) has **no caller in main**; only `T/.../ValidatorRegenIT`. A pre-existing `products` collection never receives or refreshes a validator.

"Strict schema" language for Order/Membership in ESD (e.g. `ESD:559-563`) is **application-side reconstruction**, not a Mongo validator.

---

## 8. Index manifest

All unnamed indexes get Mongo's generated name (`<field>_<dir>_…`); the code never names them, so the exact name is UNVERIFIED unless a test reads it. No collation, no `hidden`, anywhere. Every index is (re)asserted by every `bootstrap()`. Creation lines are in `SB`.

### 8.1 Catalogue / pricing / inventory

| Collection | Name | Keys (order, dir) | Unique | Sparse | Partial | TTL | Line | Query needing it | Test asserts |
|---|---|---|---|---|---|---|---|---|---|
| products | generated | `classification.vertical_id, lifecycle, classification.status, _id` all ASC (`PAG2_PRODUCT_INDEX_KEYS`) | no | no | no | — | :167 | consumer/commerce list + `_id` cursor | `ProductIndexMigrationIT` (exact order, `_id` last) |
| products | generated | `bundle_contents.component_product_id` | no | **yes** | no | — | :169-170 | `MergeService.repointBundles:131` | no |
| products | generated | `variant_group_id` | no | **yes** | no | — | :171-172 | no reader in main | no |
| products (legacy, **DROPPED at startup**) | generated | `classification.vertical_id, lifecycle, classification.status` | — | — | — | — | drop :168, impl :350-388 | superseded by PAG2 prefix | `ProductIndexMigrationIT` |
| offers_current | generated | `product_id, source, seller, channel` | **yes** | no | no | — | :173-174 | offer upsert, merge | no |
| canonical_keys | generated | `product_id` | no | no | no | — | :175 | no query in main | no |
| evidence_links | generated | `evidence_id, active` | no | no | no | — | :176 | `TaintService:102-110` | no |
| evidence_links | generated | `product_id, link_type` | no | no | no | — | :177 | no reader | no |
| classification_history | generated | `product_id, decided_at` | no | no | no | — | :178-179 | no reader | no |
| product_events | generated | `product_id, at` | no | no | no | — | :180 | no reader | no |
| work_queue | generated | `status, type` | no | no | no | — | :181 | lease claims | no |
| batches | generated | `product_id, lot_no` | **yes** | no | no | — | :182-183 | collection unused | no |
| campaign_membership | generated | `campaign_id, product_id` | **yes** | no | no | — | :184-185 | collection unused | no |
| aliases | generated | `alias_norm, lang, region` | **yes** | no | no | — | :186-187 | seed upsert, `resolveAlias`. `node_id` filter (`TaxonomyChangeService:303`) has **no index** | no |
| price_events | generated | `product_id, ts` | no | no | no | — | :188 | no reader | no |
| price_events | generated | `rolled, ts` | no | no | no | — | :189 | `RollupService` select/purge | no |
| price_current | generated | `sku_id, currency` | **yes** | no | no | — | :192-193 | point/batch read; create race | `PricingFoundationIT:195-205` |
| inventory | generated | `sku_id, fulfillment_location_id` | **yes** | no | no | — | :197-198 | all inventory R/W | `InventoryFoundationIT:291-304` |
| media_refs | generated | `owner_type, owner_id` | **yes** | no | no | — | :201-202 | all media R/W | `MediaFoundationIT:232-244` |
| service_areas | generated | `pincode` | **yes** | no | no | — | :205-206 | all R/W | `ServiceabilityFoundationIT:269-281` |
| domain_events | generated | `aggregate_type, aggregate_id, at` | no | no | no | — | :208-209 | no reader | no |
| product_card_base | generated | `sku_id` | **yes** | no | no | — | :212-213 | rebuild/readers | `ProductCardProjectionIT:455-470` |
| taxonomy_nodes | generated | `parent_id` | no | no | no | — | :214 | sibling/children checks | no |
| taxonomy_nodes | generated | `node_type, status` | no | no | no | — | :215 | no exact-fit query (UNVERIFIED) | no |
| attribute_definitions | generated | `key, version` | **yes** | no | no | — | :216-217 | `latestActiveIn` | `AttributeAuthoringIT` (snapshot) |
| attribute_schemas | generated | `schema_id, version` | **yes** | no | no | — | :218-219 | same | same |
| node_events | generated | `node_id, at` | no | no | no | — | :220 | no reader | no |
| consumer_projection_policy | generated | `vertical_id` | **yes** | no | no | — | :221-222 | `ConsumerProjectionService:129` | no |
| taxonomy_snapshot_nodes | generated | `release_id, node_id` | **yes** | no | no | — | :223-224 | `SnapshotTaxonomyReader.node` | `SnapshotIndexIT` (indirect) |
| taxonomy_snapshot_nodes | generated | `release_id, parent_id` | no | no | no | — | :225-226 | child traversal | `SnapshotIndexIT` (exact order) |
| catalogue_releases | generated | `gate` | **yes** | no | `{gate:"OPEN"}` | — | :229-231 | at most one open release | behavioural only (`RELEASE_ALREADY_OPEN`) |
| price_rollups | generated | `product_id, seller` | **yes** | no | no | — | :232-233 | rollup upsert | no |

### 8.2 Customer / order / membership

| Collection | Name | Keys | Unique | Partial | TTL | Line | Query | Test asserts |
|---|---|---|---|---|---|---|---|---|
| customer_otp_challenges | `otp_one_delivering_per_phone` | `phoneNormalized, purpose` | **yes** | `{delivering:true}` | — | :241-244 | insert race / `findDelivering` | no (behavioural `OtpServiceIT`) |
| customer_otp_challenges | `otp_one_active_per_phone` | `phoneNormalized, purpose` | **yes** | `{active:true}` | — | :245-248 | `findActive` | no |
| customer_otp_challenges | generated (expected `expiresAt_1`) | `expiresAt` | no | — | **0 s** | :255-256 | cleanup | **no** |
| customer_otp_challenges | `otp_challenge_createdat_backstop_ttl` | `createdAt` | no | — | **1 day** | :260-262 | cleanup of null-`expiresAt` rows | **no** |
| customer_otp_verified_grants | generated (expected `expiresAt_1`) | `expiresAt` | no | — | **0 s** | :265-266 | cleanup | **no** |
| customer_otp_verified_grants | generated (expected `challengeId_1`) | `challengeId` | **yes** | — | — | :270-271 | `findByChallengeId` | behavioural `OtpServiceIT:835` |
| customers | `customer_one_per_phone` | `phoneNormalized` | **yes** | — | — | :275-276 | `resolveOrCreate` upsert | no |
| customer_sessions | `session_by_customer` | `customerId` | no | — | — | :280-281 | no current query (comment :277-279) | no |
| customer_sessions | `session_expiry_ttl` | `expiresAt` | no | — | **0 s** | :285-287 | cleanup | **no** |
| customer_addresses | `address_by_customer_updated` | `customerId ASC, updatedAt DESC, _id ASC` | no | — | — | :291-294 | `findAllByCustomer` sort | no |
| checkout_quotes | `checkout_quote_one_per_idempotency_key` | `customerId, idempotencyKeyDigest` | **yes** | — | — | :297-299 | idempotent create | behavioural `CheckoutQuoteIT` |
| inventory_reservations | `inventory_reservation_one_per_order` | `orderId` | **yes** | — | — | :302-304 | `findByOrderId` | behavioural `InventoryReservationServiceIT:197` |
| inventory_reservations | `inventory_reservation_expiry` | `status, expiresAt` | no | — | — | :306-307 | `findExpiredBatch` | no |
| orders | `order_one_per_quote` | `customerId, quoteId` | **yes** | — | — | :310-312 | replay / one order per quote | behavioural `OrderServiceIT:215-228` |
| memberships | `membership_one_open_per_customer` | `customerId` | **yes** | `{openTerm:true}` | — | :317-319 | `findOpenByCustomer` | **yes** `MembershipRepositoryIT:56-85,139` |
| memberships | `membership_one_per_grant_reference` | `grantSource, grantRef` | **yes** | — | — | :322-324 | grant idempotency | yes |
| memberships | `membership_active_by_customer` | `customerId, status` | no | — | — | :329-330 | entitlement candidate read (IXSCAN asserted `:139,150`) | yes |

No index (beyond `_id`): `customer_profiles`, `customer_address_state`, `customer_carts`, `evidence`, `gtin_registry`, `identity_keys`, `system_config`, `id_sequences`, `discriminating_attributes`, `brands`, `variant_groups`, `marketplace_crosswalks`, `campaigns`, `rollup_state`, `attachment_registry`, `media_refs` beyond the unique key.

Deliberately **no TTL** on `customer_carts`, `checkout_quotes`, `inventory_reservations`, `orders`, `memberships` (comments `SB:88-114`; `MembershipRepositoryIT:76` asserts no `expireAfterSeconds`). There is **no** unique `idempotencyKey` on orders; the order idempotency key is `(customerId, quoteId)` (`OrderController.java:26-28`).

TTL indexes in the whole repo: exactly four (`SB:255-256,260-262,265-266,285-287`), all auth/OTP. Obsolete-index handling: exactly one — the legacy `products` prefix index (`SB:350-388`), whitelist match on `{v,key,name}` only so any extra option leaves it alone.

---

## 9. Bootstrap operations (execution order)

| # | Collection | Operation | Source | Idempotent | Destructive | Safe retry | Mongo privilege | Failure effect |
|---|---|---|---|---|---|---|---|---|
| 1 | db | `listCollectionNames` | `SB:150` | yes | no | yes | listCollections | abort startup |
| 2 | products | `createCollection` + `$jsonSchema` (STRICT/ERROR) if absent | `SB:152-158` | yes (guarded; check-then-create race, `NamespaceExists` uncaught — UNVERIFIED in practice) | no | yes | createCollection | abort |
| 3 | 48 others | `createCollection` if absent, no options | `SB:159-163` | same | no | yes | createCollection | abort |
| 4 | products | `createIndex` PAG2 (4 keys) | `SB:167` | yes | no | yes | createIndex | abort |
| 5 | products | `listIndexes` → `dropIndex` legacy 3-key index | `SB:168,350-388` | yes (no match = no-op) | **YES** (exact legacy match only) | yes; created before dropped | listIndexes, **dropIndex** | abort |
| 6 | all others | `createIndex` in source order | `SB:169-330` | yes if spec identical; same-key/name with different options throws `IndexOptionsConflict`/`IndexKeySpecsConflict`, uncaught | no | yes | createIndex | abort |
| 7 | discriminating_attributes | `find({status:active})` | `DiscriminatingAttributeRegistry:49` | yes | no | yes | find | abort; missing collection = 0 rows |
| 8 | taxonomy_nodes | per node `updateOne($setOnInsert, upsert)` | `TaxonomyLoader:26-35` | yes (insert-only) | no | yes | find, insert, update | abort |
| 9 | aliases | `updateOne($setOnInsert, upsert)` on `(alias_norm,lang,region)` | `:36-44` | yes | no | yes | same | abort |
| 10 | attribute_definitions | `$setOnInsert` upsert on `(key,version)`, `status:active` | `:45-52` | yes | no | yes | same | abort |
| 11 | attribute_schemas | `$setOnInsert` upsert on `(schema_id,version)`, `status:active` | `:53-60` | yes | no | yes | same | abort |
| 12 | attribute_schemas | **`updateMany`** `fields.$[q].required=false` for `pack_size`,`pack_unit` on **all** matching docs, every start | `:75-80` | re-runnable, **not insert-only** | **mutating overwrite** | yes | update | abort |

Not done at bootstrap: no `collMod`; no seed of `catalogue_releases`, `system_config`, `attachment_registry`, `consumer_projection_policy`, `discriminating_attributes`; no `id_sequences` row (lazy).

Required Mongo privileges (derived): `listCollections, createCollection, createIndex, listIndexes, dropIndex, find, insert, update` on the application DB. `ESD:658-665` gate wording mentions only "collection/index privileges" — `dropIndex` is not called out (§20 R6).

Seed resource `R/taxonomy_v0_9_0_seed.json` (9,797 lines): version `0.9.0`; 460 nodes (295 vertical, 108 sub_category, 50 category, 7 super_category), holding verticals `TZV-UNCLASSIFIED` and `TZV-SCOPE-BLOCKED`; 25 aliases; 110 attribute definitions (enum_open 67, boolean 26, number 14, string 3); 48 attribute schemas (`scope:"sub_category"`, version 1).

Other startup-time schedulers (gated by `tazzzo.scheduler.enabled`, default **true**): `CatalogSchedulers` mergeFinalizer 30 s, taintCascade 30 s, stampFanOut 30 s, canonicalKeyBackfill 30 s (not in yml), priceRollup 1 h (`J/catalog/ops/CatalogSchedulers.java`); pool size 4. **Off by default** (flags absent from yml): `tazzzo.scheduler.card-projection-enabled`, `tazzzo.scheduler.inventory-reservation-expiry-enabled`, `tazzzo.freshness.enabled` (`CommerceProjectionScheduler:41-43`, `InventoryReservationScheduler:17-19`, `CommerceFreshnessConfig:33`). No migration framework (Flyway/Mongock/mongobee) exists.

---

## 10. Transaction map

Infrastructure: only `J/catalog/tx/Tx.java` starts transactions: `run(Consumer<ClientSession>)` `:28-35`, `call(Function)` `:44-48`, both `session.withTransaction(...)` with **no `TransactionOptions`** → driver/URI defaults for read/write concern and `maxCommitTime`. Retry is entirely the driver's `withTransaction` loop (transient-error callback re-run; UnknownTransactionCommitResult commit retry is driver behaviour, not asserted in repo). No app-level retry/backoff. Holder pattern contrary to `Tx` Javadoc still exists outside auth (`RollupService:65-69`, `EvidenceService:86,92`, `AttributeAuthoringService:64,75,134,169,180`, `TaxonomyChangeService:314,525`, `TaintService:71,138`, `InventoryService:116,235,258,278`, `PricingService:148`, `MediaService:120`); each appears to assign every attempt (benign), guard test covers `auth` only. Callbacks must be retry-safe; IDs and prepared results are fixed before the Tx.

### 10.1 Customer / membership / order

| Transaction | Entry | Writes in order | Rollback / retry | Duplicate-key / idempotency / CAS |
|---|---|---|---|---|
| OTP delivery activation | `OtpService.createAndDeliver` `:110-171` (Tx `:141-157`) | *Pre-Tx, non-transactional:* insert PENDING_DELIVERY; provider send; `markDeliveryFailed`. *In Tx:* supersede old ACTIVE → PENDING→ACTIVE (`OtpChallengeRepository:141-160`) | null second write throws `OtpTransactionAbortedException`; after confirmed send a failed Tx leaves PENDING (reclaimable after `deliveryDeadline`) | partial unique `delivering`; stale holder → `retireIfStale` + one re-insert (`:186-202`) |
| OTP verify | `OtpService.verify` `:209-281` (Tx `:253-260`) | wrong-attempt/expiry writes are **non-transactional**; Tx: `tryMarkVerified` → insert grant | both or neither; null CAS → NOT_VERIFIED | unique `challengeId`; identical grant = no-op, mismatch = UNAVAILABLE |
| Session create | `CustomerSessionService.establishSession` `:66-125` (Tx `:94-109`) | consume grant (first write) → upsert `customers` → insert `customer_sessions` | full rollback; tokens issued after commit | grant filter `consumedAt==null`; unique `phoneNormalized` + upsert. Javadoc claim of server auto-retry for racing upserts (`CustomerRepository:21-28`) is UNVERIFIED in-Tx |
| Session rotate | `doRefresh` `:137-183` | non-tx read, then single CAS `tryRotateRefresh` | n/a | CAS on presented digest |
| Session revoke | `logout` `:186-190` | non-tx idempotent update | n/a | `revokedAt==null` |
| Profile write | `CustomerProfileService.patch` `:95-120` | read `customers`, upsert/CAS `customer_profiles` | UNAVAILABLE / PRECONDITION_FAILED | `{_id,version}`; 11000 → null |
| Address create/patch/delete/default | `AddressService` `:111-295` | `customers` read; `customer_address_state` limit CAS; `customer_addresses` insert/CAS/delete; default pointer | limit hit throws inside callback → rollback | version CAS; single-doc state CAS serialises |
| Cart mutate | `CartService` `:118-175` | read `customers`, read cart, `requireVersion`, insert/`replaceItemsIfVersion` | CartFailure aborts | version CAS; first-create 11000 → PRECONDITION_FAILED |
| Cart GET housekeeping | `CartService.get/clearExpired` `:69-101` | non-tx `clearExpiredIfVersion` | failure swallowed, cart reported empty | `{_id,version,expiresAt<=now}` |
| **Checkout quote create** | `CheckoutService.createQuote` `:105-170`, `persist` Tx `:203-236` | *Pre-Tx:* idempotency lookup, cart/address reads, commerce validation, **Benefits evaluated outside the Tx**. *In Tx:* read `customers` → `checkout_quotes` by idempotency → `customer_carts` → `customer_addresses` → **insert `checkout_quotes`** | MongoException → UNAVAILABLE; corrupt data → 500 | unique `(customerId,idempotencyKeyDigest)`; replay: fingerprint mismatch 409, expired 410, else original; 11000 outside Tx resolves winner (`:237-246`) |
| **Order placement (COD)** | `OrderService.placeCodOrder` `:182-195`, `execute` `:226-276`, Tx `:249-269`, `finishCod` `:335-373` | *Pre-Tx:* read orders, read quote, new OrderId, `reservationPort.prepare`. *In Tx:* (1) re-read orders; (2) preflight (quote expiry, `customers`); (3) cart-purchased check; (4) `validateAndReserve`: address, `service_areas`, `price_current`, `products`, **Benefits in-session (reads `memberships`)**, money build, `inventory`+`inventory_reservations` reserve (header last); (5) consume (reservation RESERVED→CONSUMED); (6) `cartPurchase.finalizePurchase` (`clearPurchasedIfVersion` + `$max purchasedThroughVersion`); (7) **insert `orders`** | one commit or full rollback; MongoException → UNAVAILABLE | unique `(customerId,quoteId)`; 11000 outside Tx → `recoverFromDuplicateKey` re-read winner (`:291-299`), none → INTEGRITY_FAILURE; CREATED row on COD replay → INTEGRITY_FAILURE; `purchasedThroughVersion >= quote.cartVersion` → CART_VERSION_ALREADY_PURCHASED |
| Order create-only (internal) | `OrderService.createOrder` `:160-173` | as COD through reserve, insert CREATED | single Tx | same index; no customer-reachable caller |
| **Membership grant** | `MembershipService.grant` `:75-88`, Tx `:121`, `grantInSession` `:143-180` | read by grant ref → plan check → `findOpenByCustomer` (in-window ⇒ ALREADY_ACTIVE, else `expireIfDue` CAS) → insert | stale-expiry + insert atomic; CAS miss → INTEGRITY_FAILURE | unique `(grantSource,grantRef)` + partial unique `openTerm`; replay input `(customerId,planId,planVersion)` else GRANT_REF_CONFLICT; on 11000 primary-read recovery, one bounded retry (`:119-139,186-201`) |
| **Membership termination** | `MembershipTerminationService` `:76-172` | session read → one CAS (`markCancelRequested`/`markRevoked`) → same-session re-read | CAS miss → INTEGRITY_FAILURE | idempotent cancel/revoke; no caller outside `membership` |
| Admin audit write | `ServiceabilityService.upsertServiceArea` `:143-200` | `domain_events` insert first → `service_areas` insert/CAS | audit rolls back with state | no main caller, no actor supplied |

### 10.2 Catalogue / pricing / inventory

| Transaction | Writes (order as coded) | Notes |
|---|---|---|
| `MintService.mint` (`J/catalog/tx:43-121`; governance validate runs **before** the Tx) | `identity_keys`/`gtin_registry` → `canonical_keys` or `work_queue identity_incomplete` → `products` → `classification_history` → `work_queue` governance items (`replaceOne` upsert) → review item; each `auxWrite` appends a `product_events` row | E11000 on identity/gtin/canonical → `IdentityCollisionException`; product `_id` dup **not** caught (raw `MongoWriteException`); not idempotent; re-mint resets completed governance item to pending |
| `VariantPackService`, `BundleService` | canonical_keys/work_queue → products | no `classification_history`; no nesting guard for variant_pack as bundle component |
| `ClassifyService`, `PublishService`, `GtinBindService`, `ProductUpdateService`, `ProductLifecycleService` | CAS-update `products` (+ history/evidence/registry) | `casUpdateWithEvent` filter `{_id, version:(int)expected}`; classify/publish CAS uses the in-Tx read version (always passes); title/lifecycle use caller `expectedVersion` |
| `MergeService.startMerge` / `runFinalizer` | CAS loser+survivor, `identity_keys` redirect, `work_queue` outbox; finalizer: lease claim **outside** Tx, repoint offers/bundles, CAS to merged/active, complete item **inside** Tx | resumable; outbox `_id` dup not caught |
| `TaintService`, `EvidenceService`, `CanonicalKeyBackfillService`, `TaxonomyChangeService.runStampWorker` | per-batch Tx with deterministic `work_queue` `_id` upserts + checkpoint | lease claim outside Tx; backfill events unattributed |
| `AttributeAuthoringService` (create definition, add enum value, add schema field) | `ReleaseGate` `$inc change_seq` → definitions/schemas insert | E11000 → `DUPLICATE_DEFINITION`/`DUPLICATE_SCHEMA_VERSION` |
| `TaxonomyChangeService` open/activate/rename/move/merge/split/deprecate/revive | `catalogue_releases`, `taxonomy_nodes` CAS, `node_events`, `aliases`, `work_queue stamp_scan`, `id_sequences`, snapshot upserts, `system_config` pointer | `activateRelease` is a **multi-Tx state machine** (freeze CAS → load all nodes into memory outside Tx → 200-node snapshot batches → final Tx activates defs/schemas, flips release, moves pointer); crash leaves `freezing` and blocks changes until re-run |
| `RollupService.rollup` / `purge` | `price_rollups` upsert + `price_events $set rolled` per event, 2 `product_events` rows per event; then `deleteMany({rolled:true})` | unbounded in-memory read in one Tx — see §13 |
| `OffersService.upsertOffer` | `price_events` insert → `offers_current` upsert | no CAS; no main caller |
| `PricingService.upsertPrice` (`J/pricing:122-186`) | `price_events` insert → `price_current` insert/CAS → `work_queue card_rebuild` | CAS miss/dup rolls back the ledger row; **no main caller** |
| `InventoryService.setInventory` / reserve / release / consume primitives | `inventory` CAS (`reserved <= newOnHand`); reserve filter `active AND (on_hand-reserved)>=qty` via `$expr`; event row first | session-form primitives roll back with caller |
| `InventoryReservationService` reserve/release/consume | per-SKU (sorted) primitives → header insert **last**; CAS header on `status` | idempotency by `orderId` + SHA-256 fingerprint; `resolveDuplicateWinner` (`:368`); any escaping `MongoException` → UNAVAILABLE |
| `InventoryReservationExpiryWorker` | per-header `releaseWithOutcome` own Tx | **disabled by default** |
| `MediaService.upsertMediaSet`, `ServiceabilityService.upsertServiceArea` | CAS/insert (+ `domain_events` for serviceability) | no main callers |
| `ProjectionRebuildQueue.requestRebuild` | `work_queue` `card_rebuild:<sku>` upsert with `request_generation`; on caller's session, not via `auxWrite` | gated by `tazzzo.freshness.enabled` (default off) |

Open question: `TaxonomyChangeService.nextVerticalId` swallows E11000 inside a Tx on `id_sequences` init (`:517-523`); MongoDB's behaviour for continuing a transaction after a caught duplicate-key error is UNVERIFIED (may abort the Tx).

---

## 11. Seed / reference data

| Source | Behaviour | Status |
|---|---|---|
| `TaxonomyLoader` (taxonomy v0.9.0) | insert-only `$setOnInsert` upserts for nodes, aliases, definitions, schemas on every start | VERIFIED |
| `TaxonomyLoader:75-80` | **unconditional `updateMany`** setting `required=false` for `pack_size`/`pack_unit` on every matching schema doc, any version, every start. Conflicts with "never clobbers" comment (`:27,157`) and "versions immutable" (`AttributeAuthoringService:28-29`) | **CONFLICT — INVESTIGATION REQUIRED** |
| `catalogue_releases` / `system_config.consumer_taxonomy_release` | **no baseline on a fresh DB**; `TaxonomyChangeService.recordBaseline` has no caller. `ConsumerReleaseResolver:42-56` throws `Unavailable` until an admin opens+activates a release (`POST /api/v1/taxonomy/releases`, `.../publish`) | VERIFIED; out-of-band seeding UNVERIFIED |
| `discriminating_attributes`, `consumer_projection_policy`, `attachment_registry`, `system_config languages` | read-only / never seeded in code | VERIFIED |
| Config-as-data | Membership plans (`tazzzo.membership.plans`, launch plan `TAZZZO_PLUS_MONTHLY` v1 9900 paise INR 1 month), Benefits rules (`tazzzo.benefits.rules`, default none), admin allowlist | VERIFIED (`R/application.yml:109-127`) |

---

## 12. Event-ledger inventory

| Ledger | Collection | Authority | Row shape | Ordering | Actor | Tx relation | Retention | Writers / readers |
|---|---|---|---|---|---|---|---|---|
| Product-rail audit | `product_events` | audit only; **no replay anywhere** | `{type, product_id, detail, at, actor?}` | by `at` only; no sequence | present for admin requests/system workers, **absent** for inventory, pricing w/o actor, media, rollup, CK backfill | same session, appended **before** the state write; rolls back with Tx | **none** (no TTL/job/delete) | every `WritePath` write; **no reader in main** |
| — misuse of `product_id` | same | `TZP-SYSTEM` for taxonomy/attribute/evidence/taint/stamp/rollup; SKU for pricing/inventory/media; verticalId for `CK_BACKFILL_*` | | | | | | |
| Classification history | `classification_history` | authoritative history of decisions | `{product_id, vertical_id, release_id, status, method, decided_at, confidence?}` | `(product_id, decided_at)` | none in row | same Tx, before state | none | Mint, Classify; no reader |
| Taxonomy events | `node_events` | change history | `{node_id, event renamed|re_parented|merged|split|deprecated|revived, release_id, detail, at, actor?}` | `(node_id, at)` | optional | same Tx as node CAS | none | `TaxonomyChangeService`; activation/freeze/open events go to `product_events`, not here |
| Neutral audit | `domain_events` | audit for non-product aggregates | `{aggregate_type, aggregate_id, type, detail, at, actor?}` | `(aggregate_type, aggregate_id, at)` | optional; serviceability passes none | same Tx, before state; discipline not compiler-enforced | none ("insert-only" documented) | `DomainAudit` via `ServiceabilityService`; no reader |
| **Price ledger** | `price_events` | paise ledger (`PricingService`) + legacy offer ledger (`OffersService`) | two shapes (§3.3) | `(product_id, ts)`, `(rolled, ts)` | optional on paise rows only | ledger row first, then state, same Tx | **rolled up and hard-deleted hourly by default** | `PricingService`, `OffersService`, `RollupService` (only reader) |

Replay: none exists. Ledger rollup/purge: **only** `price_events` (other physical deletes in main are not ledgers: `product_card_base` version-guarded `deleteOne` `ProductCardProjectionService:109`, `offers_current` `MergeService:121`, `customer_addresses` `AddressRepository:111`, `work_queue` `card_rebuild` rows). No code path deletes `product_events`, `node_events`, `domain_events`, `classification_history` or any `taxonomy_*` collection and no TTL exists on them. Customer domains write **no** `domain_events` and no actor audit (`MembershipTerminationService:28-29` states none is designed). `ActorDocuments.fromEvent` has no main caller.

---

## 13. Pricing / price-event purge finding (documented, not fixed)

Verified by direct read:

- `J/catalog/ops/CatalogSchedulers.java:87-94` `priceRollup()` is `@Scheduled(fixedDelayString="${tazzzo.scheduler.rollup-ms:3600000}")` (hourly); the class is `@ConditionalOnProperty(tazzzo.scheduler.enabled=true)` (`:35`) and `R/application.yml:17` defaults that to `${TAZZZO_SCHEDULER_ENABLED:true}`.
- It calls `RollupService.rollup()` then `purge()`.
- `RollupService.rollup(Date upTo)` (`J/catalog/tx/RollupService.java:43-61`) selects **every** `price_events` row with `ts <= now AND rolled != true` (no shape/field exclusion), upserts `price_rollups {product_id, seller}` using `ev.getInteger("price")` / `ev.getString("seller")`, then `$set rolled:true`, all in one Tx with an unbounded `into(new ArrayList<>())`.
- `purge()` (`:64-70`) runs `deleteMany({rolled:true})` — a hard delete of raw rows. Only min/max/count per `(product_id, seller)` survives.
- **Paise ledger rows from `PricingService` share the collection** (`PricingService.java:48,139`), have `ts` set, and have no `price`/`seller` field → they satisfy the rollup filter, get keyed `{product_id=sku, seller=null}`, are marked rolled, and are purged. The runtime effect of `Updates.min/max(..., null)` is UNVERIFIED. No test covers rolling up a paise ledger row.
- Latent today: nothing in main calls `PricingService.upsertPrice` (nor `OffersService.upsertOffer`), so no paise rows are written by production code yet. The hazard applies the moment any caller is added.
- Tests pin the purge as intended (`RollupStallIT`, `SchedulerIT:176`).
- **CONFLICT — INVESTIGATION REQUIRED:** `PricingService` Javadoc (`:27-31,86-88`) calls it an "immutable paise ledger"; `ProductCardProjectionService:37-39` says authoritative history "already lives in product_events / price_events / domain_events"; `CatalogSchedulers:28` says "a stalled rollup can never lose price history". All three disagree with the purge.
- Dead config: `R/application.yml:22` `rollup-lag-seconds: 120 # never aggregate events younger than this` is read by no main code (`rollup()` uses `new Date()` at `RollupService:39`); only `SchedulerIT:61` sets it.
- Side effect: `rollup()` writes 2 `product_events` rows per event (`PRICE_ROLLUP`, `product_id:"TZP-SYSTEM"`, no actor) plus one `PRICE_PURGE` row per purge.
- `price_current` itself is not purged; `version` CAS and int64 paise are enforced; nothing sets `active=false`.

---

## 14. Strictness matrix

A STRICT / B LEGACY-COMPATIBLE STRICT / C PERMISSIVE / D TEMPORARY-OPERATIONAL. "Strict" is only claimed where nothing is silently defaulted. A DB-side validator exists for `products` only.

| Collection | Class | Posture | Exact fallbacks / notes |
|---|---|---|---|
| products | A (DB) | validator `additionalProperties:false`; service reads: `CatalogCardReader:50-70` **silently defaults** missing `version`→0, missing classification→null vertical (**C on that read path**); `ConsumerEligibility` fail-closed | `ProductController.toResponse` stringifies nulls as literal `"null"` (`:176-199`) |
| orders | **B** | required with no default: `status`, `paymentMethod`, `version`, `lines`, `addressSnapshot`, numbers, dates. Absent `benefits`/`money` → null (legacy). Present-but-null/wrong-type/invalid → throw. **Quirk:** explicit-null `confirmedPaymentCondition` / `confirmedAt` treated as absent (`:118,133,135`) though the constructor still rejects inconsistent combinations | corrupt read at replay escapes as 500 |
| checkout_quotes | **B** | `addressVersion` absent → IAE (not legacy-supported); `benefits`/`money` absent → null; present-but-invalid → IAE (exact key-set); `fingerprint` absent → 409 on replay | `bps×subtotal` floor **not** re-verified on read |
| memberships | **B** | all strict, no defaults, Integer/Long only; absent `cancelRequestedAt`/`revokedAt` = pre-PR-16A-3 legacy, preserved null; present-null throws; any RuntimeException → INTEGRITY_FAILURE | plan fields not cross-checked against current config (snapshot authoritative) |
| customer_carts | **C** (marker **A**) | missing `expiresAt` → not expired; missing `items` → empty; quantity ≤0 **not rejected** on read; missing `version`/line dates → NPE. Marker present non-Number/negative → `CartPurchaseIntegrityException`; explicit null marker → treated as absent = 0 | |
| customers | **C** | existence-only; `status` never read | |
| customer_sessions | **C**, fail-closed predicates | `revokedAt:null` also matches missing field; non-date `expiresAt` → inactive; missing `refreshTokenDigest` → NPE → 500 | |
| customer_profiles | **C** | missing strings → null; missing `version` → NPE → 503; breaks PATCH (upsert collides, 11000 → PRECONDITION_FAILED) | |
| customer_addresses | **C** (label + version required) | bad label/missing version → 503; optional strings → null | order placement re-validates snapshot (IAE → 500) |
| customer_address_state | **C** | missing `addressCount` → NPE (Tx abort 503) or treated as limit; `decrement` unfloored | no reconciliation job |
| customer_otp_challenges | **C** / D | bad `status` → IAE/NPE → 500; **missing `maxAttempts` → `Integer.MAX_VALUE` (unlimited attempts)**; missing `attemptCount` → 0 | |
| customer_otp_verified_grants | **C** / D | missing `consumedAt` matches `== null` ⇒ consumable | |
| price_current | **C-ish** | `active` missing → false; missing `effective_*` → null; missing paise/version → NPE; invalid Price → IAE | |
| inventory | **C-ish** | `active` missing → false; counters required (NPE); invariants enforced | |
| inventory_reservations | **A** | no fallback, fails loud | |
| media_refs / service_areas | **C-ish** | missing `active` → false; missing `assets`/`routes` → empty list; Long `width` → ClassCastException | |
| product_card_base | **A** | fail-fast, nothing defaulted | |
| consumer_projection_policy | **A** | strict parser, 503 | |
| attribute_definitions / attribute_schemas | **C** | legacy absent `status` ⇒ active; write-side `valueMatchesType` accepts unknown type (`default → true`) while consumer read fails closed | schema validation strict only when a schema resolves (else lenient + `validation_gap`) |
| taxonomy_nodes / snapshot / releases / system_config / aliases / id_sequences | **C** | no validator; service logic only; snapshot reader fails closed | |
| domain_events | **B** (actor) | absent actor → Optional.empty; present must be exact 4-key doc | no reader |
| product_events / node_events / classification_history | **C** | no reader | |
| work_queue, evidence, evidence_links | **D / C** | unknown fields accepted | |

Unknown top-level keys are ignored on read in every customer/membership repository; strict key-set checks exist only inside nested benefit/money snapshots and `actor`.

## 15. Legacy compatibility matrix

| Collection | Historical shape | Handling | Synthesized? | Source |
|---|---|---|---|---|
| checkout_quotes | `benefits` absent | read as null; HTTP omits preview; never "no benefit" | no | `CheckoutQuoteRepository:109-113` |
| checkout_quotes | `money` absent | null; never synthesized payable | no | `:114-116`, `CheckoutQuote:39-55,146-156` |
| checkout_quotes | `addressVersion` absent | **unsupported** → IAE/500 | — | `:105-108` |
| orders | `benefits` / `money` absent | null; DTO omits `money`; never a zero payable | no | `OrderRepository:120-126`, `CustomerOrderDto:18,68` |
| orders | pre-PR-15A-1 rows (no `version`/`paymentMethod`) | **unsupported** (hard fail); deploy gated on `orders` count == 0 | — | `Order.java:22-25`; ESD:541-579 |
| memberships | `cancelRequestedAt`/`revokedAt` absent | preserved null | no | `MembershipRepository:276-280` |
| customer_carts | `purchasedThroughVersion` absent | treated 0 | no (not backfilled) | `CartPurchaseService:80-84` |
| customer_carts | `expiresAt` absent | not expired | no | `CartService:302-305` |
| events | `actor` absent | Optional.empty ("never guessed") | no | `ActorDocuments:13-17,47-49` |
| products | `identity.canonical_key` absent | backfill worker; null-check in `backfillOne` | write-once | `CanonicalKeyBackfillService` |
| attribute_definitions | `status` absent | treated as active | no | `AttributeAuthoringService:226-228` |
| price_events | legacy shape (unit "legacy-unknown") vs paise shape | co-exist in one collection | — | `PricingService:28-29` |
| OTP challenges | explicit nulls for activation-time fields | normal for PENDING_DELIVERY / DELIVERY_FAILED | — | `OtpChallengeRepository:78-86` |
| OTP grants / sessions | missing `consumedAt` / `revokedAt` | match `== null` filters | — | `OtpVerifiedGrantRepository:99-105` |

Historical facts are preserved, not recomputed, for quotes, orders and memberships.

---

## 16. Checkout / Order money and snapshot behaviour (current main)

- **Merchandise subtotal.** Quote: `Σ unitPricePaise × quantity` with `multiplyExact`, each line INR (`CheckoutService:362-392`); stored top-level `subtotalPaise` + `items[].unitPricePaise/lineTotalPaise`. Order: `subtotalPaise = quote.subtotalPaise()`; lines copied from the quote after `unitPricePaise` is re-verified equal to current `price_current.sellingPricePaise` (else PRICE_CHANGED; `OrderDraftAssembler:117-126`). Stored, not freshly recomputed (equal by revalidation).
- **Benefit discount.** Quote: Benefits evaluated through the **standalone** port **outside** any Tx (`CheckoutService:179-197`), copied into `benefits` + `money`. Order: **re-evaluated inside the placement Tx** via `TransactionalBenefitsEvaluationPort` over the sum of line totals (`OrderDraftAssembler:142,184-203`); the Order **never reads the quote's `benefits` or `money`**. Rule: `floor(subtotal × bps / 10000)` matched on exact `(planId, planVersion)` (`BenefitEvaluator:17-40`, `DiscountBps:25-31`).
- **Payable.** `payablePaise = merchandiseSubtotalPaise − benefitDiscountPaise`, stored explicitly in nested `money` on quotes and orders and **verified on reconstruction** (`Checkout/OrderMoneySnapshotCodec:42-45`). Zero payable valid. No tax/fee/coupon/coin/wallet fields. Top-level `subtotalPaise` stays pre-discount; no top-level payable.
- **Currency.** Top-level `"INR"` enforced in `CheckoutQuote`/`Order` constructors; none inside `money`/`benefits`. Non-INR Benefits result → IAE (`CheckoutBenefitSnapshot:68-70`, `OrderBenefitSnapshot:75-77`).
- **Benefits snapshot.** Quote: NO_BENEFIT `{outcome, eligibleSubtotalPaise, noBenefitReason}` or APPLIED `{…, discountPaise, discountBps}` — **no membership/plan identity** (`CheckoutBenefitSnapshot:17-19`). Order APPLIED additionally carries `membershipId`, `planId`, `planVersion` (`OrderBenefitSnapshot:38-63`); plan price/period live only in the `memberships` term.
- **Stored vs recomputed on read.** Only `payable == subtotal − discount` is re-derived and compared; `bps × subtotal` is **not** re-verified on reconstruction; `eligibleSubtotal == subtotal`, `money.merchandiseSubtotal == subtotal`, `money.discount == benefit discount` are enforced in constructors.
- **Docs vs code.** No conflict with ESD:970-1005, 1011-1034, 1075-1102 (Order authoritative, Checkout advisory, no `PAYABLE_CHANGED`). **CONFLICT (stale Javadoc, not behaviour):** `OrderRepository.java:99-103` ("no fallback for a malformed/legacy row") and `Order.java:22-24` ("NO compatibility shim") contradict the explicit legacy-null handling for `benefits`/`money` at `OrderRepository:120-126` and `Order:107-127`.

---

## 17. Configuration findings

All from checked-in config; **none proves live config**. No secrets were found in the files read.

| Item | Finding | Status |
|---|---|---|
| DB name / URI | `${MONGODB_DATABASE:tazzzo}`, `${MONGODB_URI:mongodb://localhost:27017/tazzzo?replicaSet=rs0}` (no credentials, only `replicaSet=rs0`) | VERIFIED |
| readPreference | not set anywhere except `MembershipRepository:64` primary pin (non-session reads only; session reads follow the Tx) | VERIFIED; deployed URI UNVERIFIED |
| readConcern / writeConcern / retryWrites / retryReads / maxCommitTime / TransactionOptions | **not set** anywhere | VERIFIED absent |
| Pool / timeouts / TLS / auth options | not set anywhere | VERIFIED absent |
| Bootstrap flags | `tazzzo.schema.bootstrap-on-startup: true`, `load-taxonomy-seed: true`, hard-coded in yml, not env-overridable there | VERIFIED |
| Scheduler | `enabled: ${TAZZZO_SCHEDULER_ENABLED:true}`; rollup 3,600,000 ms; `rollup-lag-seconds: 120` (dead); pool 4 | VERIFIED |
| Profiles | none (no profile yml; no `@Profile` use in main) | VERIFIED |
| Env example | `.env.local.example:11-12,15,38` | VERIFIED |
| CI | `.github/workflows/backend-ci.yml`: OpenAPI validate + `./mvnw -B clean test` on Java 21; Mongo via Testcontainers (runner Docker); no deploy step, no DB gate checks | VERIFIED |
| Tests' config | all contexts `bootstrap-on-startup=false`, `scheduler.enabled=false`; `load-taxonomy-seed` **not overridden** (stays true); each test `drop()` + manual `bootstrap` | VERIFIED |

Net default-prod effect: bootstrap + seed on every instance start; CatalogSchedulers on (incl. price purge); projection rebuild/reconcile, reservation expiry and write-path rebuild-queue hook **off**.

---

## 18. Test coverage

Harness: Testcontainers `new MongoDBContainer("mongo:7")` `getReplicaSetUrl()` (single-node RS, floating tag); one static container per JVM in `AbstractMongoIT`; separate containers in `AbstractApiIT`, `AbstractConsumerIT`, `SchedulerIT`, `CustomerAuthFilterIT`, `CrossSurfaceAuthIsolationIT`; Redis `redis:7-alpine`. ESD records 2,366 tests on merged `main` CI (`ESD:1193,1400,1402`; 2,382 on the then-open feature branch, `ESD:1395`) — **not re-run in DB-0**. Many test classes run on a Mongo container (not counted exactly).

| Area | Evidence |
|---|---|
| Index / obsolete-index / bootstrap idempotency | `ProductIndexMigrationIT:55-229` (fresh PAG2, legacy dropped incl. non-default name, option-bearing legacy kept, unknown-field kept, TTL-bearing rejected, direction, rerun no-op, old/new never coexist, `_id` last); `SnapshotIndexIT`; `MembershipRepositoryIT:56-150` (exactly 3 indexes + `_id`, partial/unique, no TTL, IXSCAN via explain, DB rejects 2nd open term); single-collection idempotency in `InventoryFoundationIT:292`, `PricingFoundationIT:195`, `MediaFoundationIT:233`, `ServiceabilityFoundationIT:270`, `ProductCardProjectionIT:456` |
| Validator | `ValidatorContractIT` (products battery incl. "bypass fake attribute key IS accepted by Mongo"); `ValidatorRegenIT` |
| Tx commit/rollback | `TransactionAtomicityIT`; `OrderPlaceCodIT` (rollback after reserve/consume/cart finalize); `OrderServiceIT:570`; `InventoryReservationServiceIT:555-607`; `CartPurchaseServiceIT:300-326`; `MembershipServiceIT:195`; `TransactionalReadPortsIT:70-102`; `FreshnessFoundationIT:401` |
| Transient retry | `RetryInjectingTx` (`T/catalog/tx`, throws TRANSIENT-labelled exception **after** body, no server failpoints); `TxCallRetryContractIT`, `OtpTransactionRetryIT`, `SessionTransactionRetryIT`, Membership*, Checkout/Order/Address/Cart/Profile retry ITs |
| Duplicate-key recovery | `MembershipDuplicateKeyRecoveryIT`, `OrderPlaceCodIT:1110-1140`, `CheckoutQuoteIT:527,765,1373-1404`, `OtpServiceIT`, `CanonicalKeyIT:153`, `*FoundationIT` |
| Replay / idempotency | Membership, Checkout, Order (`OrderServiceIT:230-336`), reservation, scheduler, backfill, OTP resend |
| Concurrency (real races) | Membership(Termination)ConcurrencyIT, OtpServiceIT, CustomerSessionServiceIT, Address/Cart/Profile, Checkout/Order, Inventory*, Media, Serviceability, ProductCardProjection, FreshnessFoundation, SchedulerIT:206 |
| Reconstruction / corrupt data | `MembershipReconstructionTest` (missing fields, wrong types, unknown extra fields ignored, legacy row, impossible REVOKED), `MembershipEntitlementIT:305-427`, `ProductCardProjectionIT:446`, `ConsumerTopologyCorruptionIT`, `OrderServiceIT:258`, `CartPurchaseServiceIT:278`, `Checkout/OrderMoneySnapshotTest`, `OrderContractTest`, `CheckoutQuoteContractTest` |
| Explicit null | PATCH-null semantics only (`AddressServiceIT:295,308`, `CustomerProfileServiceIT:172,181`), not stored-null reads |
| Money BSON type | `PricingFoundationIT:41,186`, `InventoryFoundationIT:57`, `MediaFoundationIT:137`, `MoneyTest` |
| Read preference | only `MembershipRepositoryIT:187-190` (handle property incl. override of drifted `secondaryPreferred`) |

### Missing coverage (gaps, not to be written in DB-0)

1. **No TTL assertion** for any of the four TTL indexes (OTP challenges ×2, OTP grants, sessions).
2. **No direct `listIndexes` assertion** for `customers, customer_sessions, customer_addresses, checkout_quotes, orders, inventory_reservations, catalogue_releases` gate, both OTP partial indexes (behavioural only).
3. No production-config test: no context runs with `bootstrap-on-startup=true`/scheduler on at start.
4. No validator on any non-product collection ⇒ no validator-vs-legacy-data test; no test that bootstrap detects a stale/missing validator on a pre-existing `products`.
5. No test for `createIndex` option-conflict against a pre-existing index, nor concurrent two-instance bootstrap (`createCollection` race).
6. No test seeding a pre-existing `orders` collection (the gate's actual risk); only PAG-2 legacy-index migration is tested.
7. Retry is **simulated only**: no real server failpoint, no UnknownTransactionCommitResult test, no 120 s timeout test.
8. Single node, floating `mongo:7`: no multi-node RS, so readPreference/readConcern/writeConcern effects are untested.
9. Holder-pattern guard covers `auth` only.
10. No test of URI/driver defaults (`retryWrites`, write concern) vs gate 5 assumptions.
11. No test that rolls up a **paise** `price_events` row or asserts the ledger survives purge.
12. No test that `TaxonomyLoader`'s `updateMany` leaves later authored schema versions untouched.
13. No raw-BSON-type test for `Instant`-written fields; limited wrong-type tests outside Membership.
14. CI does not check deployed DB state; gates are not machine-checked.

---

## 19. Deployment-gate statuses

The six gates exist in `ESD` (never enumerated in one block; "the total stays six" at `ESD:1064-1065`; restated `:1138,1190,1252,1280`). No new gates are introduced by DB-0.

| # | Gate (ESD) | Status | Basis |
|---|---|---|---|
| 1 | **Orders gate** — deployed persistent `orders` collection must have document count == 0 in every persistent environment before strict-schema deploy (`ESD:541-542,574-579`) | **UNVERIFIED** | no live evidence; repo inspection is not proof |
| 2 | Membership (1) — no pre-existing conflicting `memberships` collection/schema in each persistent environment (`ESD:658-665`) | **UNVERIFIED** | live-only |
| 3 | Membership (2) — `SchemaBootstrap` has collection/index privileges | **UNVERIFIED** | live-only. Note code also needs `dropIndex` (§20 R6) |
| 4 | Membership (3) — effective `tazzzo.membership.plans`, `tazzzo.benefits.rules`, `tazzzo.admin.oidc.*`, `tazzzo.admin.users` identical across environments/instances | **UNVERIFIED** | live-only |
| 5 | Membership (4) — deployed Mongo URI has no unexpected `readPreference` override | **UNVERIFIED** | repo sets none; deployed URI unseen |
| 6 | Membership (5) — deployed cluster default read/write concern is as assumed | **UNVERIFIED** | ESD never states the assumption, so it cannot be verified from the repo |

No gate is PASS; none is FAIL (no contrary live evidence). Order placement and Benefits-aware checkout also depend on the Membership gates (`ESD:864-871,918-921`).

---

## 20. Live-environment unknowns and risks/conflicts

### 20.1 UNVERIFIED (needs Atlas/live evidence)

Deployed `MONGODB_URI` (options, readPreference, retryWrites, w, readConcern); cluster default read/write concern; server version/FCV; topology (node count, region); whether `orders`/`memberships` already exist in any environment; effective app DB user privileges (incl. `dropIndex`, `collMod`); actual generated index names; presence of out-of-band `catalogue_releases`/`system_config` baseline; effective `tazzzo.membership.plans`/`benefits.rules`/admin config per instance; whether production runs multiple instances (concurrent bootstrap, schedulers); resolved driver version and its `withTransaction` timeout; behaviour of `Updates.min/max(null)` for paise rows; backup/PITR state; Atlas network/TLS/auth config.

### 20.2 Risks / conflicts (documented only — nothing fixed in DB-0)

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| R1 | **High (latent)** | `price_events` hard-deleted hourly by default; paise ledger rows not excluded; contradicts three Javadoc claims of immutable history | §13 — **CONFLICT — INVESTIGATION REQUIRED** |
| R2 | Med | `rollup-lag-seconds` is dead config; rollup boundary is "now" | `RollupService:39`, `application.yml:22` |
| R3 | High | `TaxonomyLoader` rewrites `required` on **all** matching attribute-schema versions on every start; contradicts "insert-only/never clobbers/versions immutable" | `TaxonomyLoader:75-80` — **CONFLICT** |
| R4 | High | `products` validator applied only at creation; no `collMod` at bootstrap; `ValidatorGenerator.regenerate` has no prod caller; a pre-existing/implicit `products` never gets one | `SB:152-158`, `ValidatorGenerator:44-49` |
| R5 | High | Bootstrap + seed run on **every instance at every start**, hard-coded on; no lock; `createCollection` race and `createIndex` option-conflict are uncaught and abort startup | `application.yml:14-15`, `SB:150-330` |
| R6 | Med | Bootstrap performs a conditional `dropIndex` on `products`; not documented in ESD; gate 3 wording omits `dropIndex` privilege | `SB:168,350-388` |
| R7 | High | All concern/pref/retry/pool/timeout settings implicit; gates 5–6 unverifiable from repo | §17 |
| R8 | Med | Only `products` has a DB validator; "strict schema" for orders/memberships is application-side only; 8 customer/auth collections are posture C (carts, customers, sessions, profiles, addresses, address_state, OTP challenges, OTP grants) with raw-exception paths (e.g. OTP missing `maxAttempts` ⇒ unlimited attempts; missing session digest ⇒ NPE/500) | §14 |
| R9 | Low | `customer_address_state.decrement` has no floor; no reconciliation of `addressCount` vs addresses | `CustomerAddressStateRepository:81` |
| R10 | Med | None of the four TTL indexes is test-asserted | §18 |
| R11 | Low | Holder-pattern across `Tx.run` outside auth; guard test auth-only | §10 |
| R12 | Low | E11000 swallowed inside Tx on `id_sequences` init; MongoDB continue-after-error behaviour unproven | `TaxonomyChangeService:517-523` |
| R13 | Med | Unbounded growth: `product_events` (N rows per op, `product_id` overloaded with `TZP-SYSTEM`/SKU/vertical), `work_queue` completed rows, `domain_events`, `node_events`, `classification_history`, `checkout_quotes`, `orders`, `inventory_reservations`, `customer_sessions` (until TTL) — none has retention; ledgers have no readers | §12 |
| R14 | Low | Several indexes serve no reader in main (`canonical_keys.product_id`, `classification_history`, `product_events`, `domain_events`, `node_events`, `price_events (product_id,ts)`, `session_by_customer`, `variant_group_id`) and one filter has no index (`aliases.node_id`) | §8 |
| R15 | Med | Fresh DB has no `catalogue_releases`/consumer pointer ⇒ consumer taxonomy `Unavailable` until an admin publishes | §11 |
| R16 | Low | Projection rebuild/reconcile and reservation expiry are **off** by default; `product_card_base` freshness and stale-RESERVED cleanup depend on flags | §9 |
| R17 | Med | Seven collections created with no reader/writer (`brands, variant_groups, marketplace_crosswalks, batches, campaigns, campaign_membership, rollup_state`); `batches`/`campaign_membership` carry unique indexes | §3.1 |
| R18 | Low | `OrderRepository`/`Order` Javadoc contradicts legacy-null handling; `README.md` marks `/v1` "frozen — not implemented" and PR-07 "same-txn updates"; `ESD` header "Last updated 2026-09-28" predates its 2026-10-03 entries; `ESD` "no migration/no index" statements omit the `dropIndex` | §16, docs audit |
| R19 | Med | Test harness: floating `mongo:7`, single node, simulated retry only, no prod-config context | §18 |

---

## 21. Recommended DB execution sequence

Recommendations only; nothing here is authorised by DB-0.

1. **Decision gate before DB-1/DB-2 (needs owner ruling):** R1 (price ledger vs rollup/purge), R3 (loader rewrite), R5 (bootstrap-on-every-start). These change what "the contract" and "the index set" are.
2. **DB-1 — Collection contracts + validator policy.** Freeze the 49-collection contract from §3. Decide per-collection validator policy (today: `products` only); define `validationLevel/Action`, legacy carve-outs for `orders`/`checkout_quotes` `benefits`/`money` and `memberships` optionals; decide how an existing `products` collection gets/updates its validator (R4).
3. **DB-2 — Indexes + uniqueness + TTL.** Name all indexes explicitly; lock the §8 manifest; add assertions for the four TTL indexes and the unasserted partial/unique indexes; decide fate of unused indexes (R14) and the `aliases.node_id` gap; classify retention for ledgers (R13).
4. **DB-3 — Migration/bootstrap framework.** Separate bootstrap/migration from app start (privileged job, single runner, lock), handle `createCollection` race and index-option conflicts, retire the implicit `dropIndex`, make the seed truly insert-only (R3, R5, R6).
5. **DB-4 — Atlas staging requirements + users/security.** Separate privileged migration user from app runtime user; set explicit URI options (retryWrites, `w:majority`, readConcern, readPreference, timeouts, pool); resolve gates 2–6 with live evidence.
6. **DB-5 — Catalogue/reference ingestion.** Baseline `catalogue_releases` + consumer pointer path; seed ownership for `discriminating_attributes`, `consumer_projection_policy`, `attachment_registry`, `system_config`.
7. **DB-6 — Backup/restore + retention.** PITR/restore drill; retention for event ledgers, `work_queue`, quotes/reservations/sessions.
8. **DB-7 — Query/index/load verification.** `explain` for list/cursor, entitlement, idempotency paths; real-failpoint retry tests; multi-node RS; production-config bootstrap test.
9. **DB-8 — Production datastore readiness gate.** Re-run against live evidence; all six gates PASS before any PASS claim.

**Next DB task:** DB-1 (Collection contracts + validator policy), after the owner rules on R1/R3/R5.

---

*DB-0 changed no application code, schema, index, validator, configuration or data, and made no connection to MongoDB.*
