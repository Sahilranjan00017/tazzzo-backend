# DATABASE_INDEX_MANIFEST — tazzzo-backend (DB-2)

Index, uniqueness and TTL contract derived from real query paths and business invariants. Implementation in this phase is limited to **one additive index** and an index-contract test suite; everything else is documented, proposed or deferred.

## 1. Metadata

| Item | Value |
|---|---|
| Repository | `Sahilranjan00017/tazzzo-backend` |
| Base | `origin/main` `e3a0db6d47bc556472b42f169223eb89684d67a8` (DB-1 merged; DB-0 `52ab530`; audited source `f5b2cdd`) |
| Index count today | **48** non-`_id` indexes on 34 collections (DB-0 §8: 31 + 17). After DB-2: **49**. After DB-3 and PR #49: **60** — the 48 baseline indexes (created by `SchemaBootstrap`/migration `V0001`) plus 3 created **only by explicit migrations** (`V0002`, `V0005`, `V0006`) plus the 9 audit-read indexes (`V0007`; also created by the legacy test/dev `bootstrap`). Address-create idempotency adds 2 (`V0015`, migration-only); `docs/database/DATABASE_MIGRATION_RUNBOOK.md`) |
| Evidence | (a) three read-only query-path audits of every Mongo operation on every collection, with path:line cites; (b) a **one-off, uncommitted** `explain(executionStats)` experiment on `mongo:7` (Testcontainers replica set, synthetic data, single node) — see §3; this PR does not reproduce those figures; (c) the **committed** `IndexContractIT`, which runs against a real MongoDB 7 Testcontainers server and pins plan *shape* and index specs, not the experiment's numbers |
| In-flight work | none on indexes. Admin audit-read (PR #49) **merged** as `8227286`; its nine `audit_read_*` indexes are now pinned (§10) and created by migration `V0007` |
| Database access | none beyond local Testcontainers. No Atlas/production connection. No data or live-schema mutation |

Legend for the *Test coverage* column: **S** = exact spec asserted by `IndexContractIT` (name, key order/direction, unique, sparse, partial filter, TTL, no collation/hidden); **D** = DB-level duplicate rejection asserted; **R** = concurrent duplicate race (16 threads, exactly one winner) asserted; **E** = planner/`explain` evidence asserted by a **committed** test; **X** = observation from the **one-off, uncommitted** explain experiment (§3), **not reproduced by this PR** and not asserted by any test; **B** = behavioural service-level coverage (pre-existing); **ttl-set guard** = covered by the "only these four TTL indexes exist" assertion.

## 2. Rules applied

- **Uniqueness:** every business invariant that concurrent writers can violate must be enforced by a Mongo unique index where appropriate; application checks and upserts alone are insufficient.
- **TTL:** only genuinely temporary data. Never durable business history (orders, membership history, immutable pricing ledger, audit history, catalogue history).
- **No blind index creation:** an index is implemented only if proven by an existing query/invariant, safe for existing data, compatible with current behaviour, testable and migration-safe. A unique index that existing data could violate is **not** created; it requires a duplicate preflight and migration plan (R5).
- **R1:** paise price-ledger history is immutable and must not become purgeable merely because `rolled == true`; unknown/legacy shapes default to retention. DB-2 analyses index/query needs only and does **not** change retention semantics.
- **R5:** no destructive or mutating evolution is introduced. No index is dropped or renamed in this phase (rename = drop + create); those are DB-3 migration steps.

## 3. One-off explain experiment (uncommitted; mongo:7, Testcontainers, synthetic data; not reproduced by this PR)

Method: a **one-off, uncommitted** integration experiment seeded synthetic data, ran `explain` with `executionStats`, created candidate indexes and re-ran. **This PR does not reproduce those numbers**: the committed `IndexContractIT` uses different data (6,000 products, 30 verticals) and `queryPlanner` verbosity, so it pins the *plan shape* of the new index (uses it, no SORT, no COLLSCAN, no `_id` walk), not the 595/100/0 figures. Caveats: synthetic data, single node, one server version; plan choice can vary with data skew and version, so these are evidence for the decision, not a performance guarantee.

| Query path | Data | Current-index plan | Result | Decision |
|---|---|---|---|---|
| **Stamp / canonical-key backfill** — `vertical_id = V AND _id > cp`, sort `_id`, limit 100 (`TaxonomyChangeService:477-484`, `CanonicalKeyBackfillService:79-85`) | 60,000 products, 6 verticals | `LIMIT > PROJECTION > FETCH > IXSCAN(_id_)` (PAG2 cannot order by `_id` after a vertical-only equality) | **595 docs examined for 100 returned** (≈ total/vertical-share; the seed has 295 verticals, so the ratio grows with vertical count) | candidate `{vertical_id, _id}` → for the stamp worker's `_id`-only projection, `PROJECTION_COVERED > IXSCAN`: **100 keys, 0 docs**; the backfill has **no projection** (`CanonicalKeyBackfillService:79-85`), so it still fetches its 100-document page but no longer walks and filters the whole `_id` index. **IMPLEMENTED** |
| Consumer list, 3 verticals — `vertical_id IN, lifecycle, status, product_type IN`, sort `_id`, limit 51 (`ConsumerProductListService:145-153`) | same | `SORT_MERGE` over PAG2 (explode-for-sort) | 53 keys / 51 docs for 51 returned — ideal | none; candidate adds nothing |
| Consumer list, 156 and 306 verticals | same | planner flips to `IXSCAN(_id_)` + residual filter | 74 keys / 74 docs for 51 returned (dense eligibility) | none now; **re-check with skewed data in DB-7** (cost ∝ total/eligible) |
| Reconciler page — `lifecycle, status, product_type IN, vertical_id NIN`, `_id > cp`, sort `_id`, limit 200 (`ProjectionReconciler:123-126`) | same | `IXSCAN(_id_)` + residual | 292 docs for 200 returned | none; sweep is flag-gated (off by default) |
| Taint page, light evidence (60 links) | 18,000 links across 300 evidences + one 6,000-link evidence | `SORT > FETCH > IXSCAN(evidence_id_1_active_1)` | 60 docs, in-memory sort of ≤ 60 | candidate `{evidence_id, active, _id}` removes the sort but gains ≈ nothing at this size — **not implemented** |
| Taint page, heavy evidence | same | `IXSCAN(_id_)` | 100 keys / 100 docs | none |
| Rollup select — `ts <= now AND rolled != true` (`RollupService:46-48`) | 50,000 events, 5,000 unrolled | `FETCH > IXSCAN(rolled_1_ts_1)` | 5,001 keys for 5,000 rows — index-friendly, **not** a full scan | none (the real hazard is the unbounded in-memory read, not the index) |
| Purge — `deleteMany({rolled:true})` (`RollupService:68`) | same | `BATCHED_DELETE > FETCH > IXSCAN(rolled_1_ts_1)` | served by the same index | none — and R1 governs whether it should run at all |

Two conclusions from the static audit were **re-checked and softened**: the per-vertical scan is not O(N²) — each batch resumes from the `_id` checkpoint (readable from the code, `gt("_id", checkpoint)`), so per-batch cost is bounded by the vertical's sparsity; a one-off explain at one checkpoint confirmed the plan shape but is not a whole-scan cost measurement. Likewise the taint query resumes from its checkpoint. The `{vertical_id, _id}` index is justified by the sparsity cost; that this cost grows with the number of verticals (the seed has 295, a seed fact) is **inference**, not measured.

## 4. Index manifest — current state and DB-2 changes

The 48 baseline indexes are created by `SchemaBootstrap.bootstrap` / migration `V0001` (idempotent); since DB-3 the application no longer runs `bootstrap` at startup (mode `VERIFY`, runbook §2). Rows 2, 50 and 51 are created **only by migrations** (`V0002`, `V0005`, `V0006`), never by `bootstrap`. Names marked *(generated)* are Mongo default names derived from the key pattern; `IndexContractIT` pins them exactly. Action vocabulary: **KEEP**, **IMPLEMENTED (DB-2 / DB-3)**, **DROP-CANDIDATE** (no reader in main; a disabled drop migration exists; needs owner approval), **WAIT** (no reader in main but a decision is pending: PR #49 or an unused-collection retirement; see runbook §14), **RETAIN (R1)**.

| # | Collection | Exact name | Keys (order, direction) | Unique | Partial | Sparse | TTL | Owning query / invariant | Exists today | Bootstrap creates | Test coverage | Proposed action | Migration / preflight |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `products` | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` (generated) | vertical_id ↑, lifecycle ↑, status ↑, _id ↑ | N | — | N | — | Consumer/commerce list + `_id` keyset cursor (`ConsumerEligibility.within`, `ConsumerProductListService:145-153`, `CommerceListService:162-167`, `ConsumerVisibilityProbe:41`) | YES | YES | S, E (`ScaleIT` explain, no-sort category filter only), X (SORT_MERGE for 3 verticals) | KEEP | none (pre-existing) |
| 2 | `products` | `product_vertical_id_cursor` | classification.vertical_id ↑, _id ↑ | N | — | N | — | Per-vertical `_id`-ordered scan: stamp worker `TaxonomyChangeService:477-484` (`_id`-only projection: covered by reasoning and the one-off experiment X, not asserted by a test), `CanonicalKeyBackfillService:79-85` (no projection: index-ordered fetch) | **NO** (added by DB-2) | NO — migration `V0002` (DB-3) | S, E (`IndexContractIT`, stamp-worker shape only: plan = this index, no SORT, no COLLSCAN, no `_id_` walk) | **IMPLEMENTED (DB-2)**; owned by migration `V0002` since DB-3 | none (non-unique, additive). Live build time UNVERIFIED — measure on staging data size before production |
| 3 | `products` | `bundle_contents.component_product_id_1` (generated) | bundle_contents.component_product_id ↑ | N | — | **Y** | — | `MergeService.repointBundles:131-132` | YES | YES | S | KEEP | none |
| 4 | `products` | `variant_group_id_1` (generated) | variant_group_id ↑ | N | — | **Y** | — | none found in main (UNVERIFIED future need); `variant_groups` collection is unused | YES | YES | S | **WAIT** (see runbook §14) | none |
| 5 | `offers_current` | `product_id_1_source_1_seller_1_channel_1` (generated) | product_id ↑, source ↑, seller ↑, channel ↑ | **Y** | — | N | — | Invariant: one current offer per (product, source, seller, channel); `OffersService.upsertOffer`, `MergeService:112-117` | YES | YES | S, D | KEEP | none (already enforced at bootstrap) |
| 6 | `canonical_keys` | `product_id_1` (generated) | product_id ↑ | N | — | N | — | none (reads are `_id`-keyed); `product_id` never queried | YES | YES | S | **DROP-CANDIDATE** (disabled migration `V0102`; needs owner approval) | none |
| 7 | `evidence_links` | `evidence_id_1_active_1` (generated) | evidence_id ↑, active ↑ | N | — | N | — | `TaintService:103-108` (one-off experiment X: used with a blocking SORT over that evidence's links) | YES | YES | S | KEEP | none |
| 8 | `evidence_links` | `product_id_1_link_type_1` (generated) | product_id ↑, link_type ↑ | N | — | N | — | upsert filter prefix `ClassifyService:58`, `PublishService:64` | YES | YES | S | KEEP | none |
| 9 | `classification_history` | `product_id_1_decided_at_1` (generated) | product_id ↑, decided_at ↑ | N | — | N | — | none: insert-only, no reader in main or PR #49 | YES | YES | S | **WAIT** (see runbook §14) | none |
| 10 | `product_events` | `product_id_1_at_1` (generated) | product_id ↑, at ↑ | N | — | N | — | none in main; PR #49 uses `audit_read_*` instead. Highest-write ledger (one row per `WritePath` write) | YES | YES | S | **WAIT** (see runbook §14) | none |
| 11 | `work_queue` | `status_1_type_1` (generated) | status ↑, type ↑ | N | — | N | — | Lease claims for 5 job types (`TaintService:91-98`, `TaxonomyChangeService:463-470`, `MergeService:76-83`, `CanonicalKeyBackfillService:67-74`, `ProjectionRebuildWorker:140-152`); `lease_until` is a residual filter | YES | YES | S | KEEP | none |
| 12 | `batches` | `product_id_1_lot_no_1` (generated) | product_id ↑, lot_no ↑ | **Y** | — | N | — | collection unused in main | YES | YES | S, D | **WAIT** (see runbook §14) | none |
| 13 | `campaign_membership` | `campaign_id_1_product_id_1` (generated) | campaign_id ↑, product_id ↑ | **Y** | — | N | — | collection unused in main | YES | YES | S, D | **WAIT** (see runbook §14) | none |
| 14 | `aliases` | `alias_norm_1_lang_1_region_1` (generated) | alias_norm ↑, lang ↑, region ↑ | **Y** | — | N | — | Invariant: one alias per (norm, lang, region); seed upsert, `TaxonomyService.resolveAlias` | YES | YES | S, D | KEEP | none. Note: `aliases.node_id` (mergeNodes `updateMany`) has no index; 25 seeded rows — not proven, no action |
| 15 | `price_events` | `product_id_1_ts_1` (generated) | product_id ↑, ts ↑ | N | — | N | — | none in main (paise rows set `product_id = sku_id` so a future per-SKU history read would use it) | YES | YES | S | RETAIN (R1: durable-ledger read path) | none |
| 16 | `price_events` | `rolled_1_ts_1` (generated) | rolled ↑, ts ↑ | N | — | N | — | `RollupService:46-48` select (`rolled != true`, `ts <= now`) and `:68` purge (`rolled == true`). **One-off experiment (X, uncommitted):** select = IXSCAN 5001 keys for 5000 rows; purge = BATCHED_DELETE via this index | YES | YES | S, X | KEEP (re-assess after R1 work package) | none |
| 17 | `price_current` | `sku_id_1_currency_1` (generated) | sku_id ↑, currency ↑ | **Y** | — | N | — | Invariant: one current price per (SKU, currency); point + batch `$in` read, CAS write, create-race guard | YES | YES | S, D, R | KEEP | none |
| 18 | `price_rollups` | `product_id_1_seller_1` (generated) | product_id ↑, seller ↑ | **Y** | — | N | — | `RollupService` upsert key; write-only | YES | YES | S, D | KEEP (re-assess after R1) | none |
| 19 | `inventory` | `sku_id_1_fulfillment_location_id_1` (generated) | sku_id ↑, fulfillment_location_id ↑ | **Y** | — | N | — | Invariant: one stock row per (SKU, location); all inventory access | YES | YES | S, D | KEEP | none |
| 20 | `media_refs` | `owner_type_1_owner_id_1` (generated) | owner_type ↑, owner_id ↑ | **Y** | — | N | — | Invariant: one media set per owner | YES | YES | S, D | KEEP | none |
| 21 | `service_areas` | `pincode_1` (generated) | pincode ↑ | **Y** | — | N | — | Invariant: one routing config per PIN | YES | YES | S, D | KEEP | none |
| 22 | `product_card_base` | `sku_id_1` (generated) | sku_id ↑ | **Y** | — | N | — | Invariant: one card per SKU; reconciler paging by `sku_id` | YES | YES | S, D | KEEP | none |
| 23 | `domain_events` | `aggregate_type_1_aggregate_id_1_at_1` (generated) | aggregate_type ↑, aggregate_id ↑, at ↑ | N | — | N | — | none in main; PR #49 `targetType`+`targetId` equality could use it but it cannot supply the `(at,_id)` sort | YES | YES | S | **WAIT** (see runbook §14) | none |
| 24 | `taxonomy_nodes` | `parent_id_1` (generated) | parent_id ↑ | N | — | N | — | sibling/children checks (`TaxonomyChangeService:222-226,329-332,360-362,397-400`) | YES | YES | S | KEEP | none |
| 25 | `taxonomy_nodes` | `node_type_1_status_1` (generated) | node_type ↑, status ↑ | N | — | N | — | `TaxonomyChangeService:163-168` (leftmost prefix; residual `attribute_schema_id`); rare | YES | YES | S | KEEP (low value; re-assess in DB-3) | none |
| 26 | `attribute_definitions` | `key_1_version_1` (generated) | key ↑, version ↑ | **Y** | — | N | — | Invariant: one definition per (key, version); `latestActiveIn` reverse walk | YES | YES | S, D | KEEP | none |
| 27 | `attribute_schemas` | `schema_id_1_version_1` (generated) | schema_id ↑, version ↑ | **Y** | — | N | — | Invariant: one schema per (schema_id, version) | YES | YES | S, D | KEEP | none |
| 28 | `node_events` | `node_id_1_at_1` (generated) | node_id ↑, at ↑ | N | — | N | — | none in main; PR #49 uses `audit_read_*` | YES | YES | S | **WAIT** (see runbook §14) | none |
| 29 | `consumer_projection_policy` | `vertical_id_1` (generated) | vertical_id ↑ | **Y** | — | N | — | Invariant: one policy per vertical (`policiesFor` resolves duplicates last-wins, so this index is the only guard) | YES | YES | S, D | KEEP | none |
| 30 | `taxonomy_snapshot_nodes` | `release_id_1_node_id_1` (generated) | release_id ↑, node_id ↑ | **Y** | — | N | — | Invariant: one snapshot node per (release, node); activation upsert | YES | YES | S, D | KEEP | none |
| 31 | `taxonomy_snapshot_nodes` | `release_id_1_parent_id_1` (generated) | release_id ↑, parent_id ↑ | N | — | N | — | `SnapshotTaxonomyReader` children/subtree walks | YES | YES | S | KEEP | none |
| 32 | `catalogue_releases` | `gate_1` (generated) | gate ↑ | **Y** | `{gate:"OPEN"}` | N | — | Invariant: at most one open release (publishing or freezing); activation `$unset gate`. `ReleaseGate` `status=publishing` lookup is NOT served by this partial index (tiny collection) | YES | YES | S, D, R, partial semantics | KEEP | none |
| 33 | `customer_otp_challenges` | `otp_one_delivering_per_phone` | phoneNormalized ↑, purpose ↑ | **Y** | `{delivering:true}` | N | — | Invariant: one in-flight delivery per (phone, purpose); `findDelivering` filter carries `delivering:true` | YES | YES | S, D, R, partial semantics | KEEP | none |
| 34 | `customer_otp_challenges` | `otp_one_active_per_phone` | phoneNormalized ↑, purpose ↑ | **Y** | `{active:true}` | N | — | Invariant: one ACTIVE challenge per (phone, purpose); `findActive` (every OTP request) carries `active:true` | YES | YES | S, D, partial semantics | KEEP | none |
| 35 | `customer_otp_challenges` | `expiresAt_1` (generated) | expiresAt ↑ | N | — | N | **0 s** | TTL cleanup of activated challenges. Null `expiresAt` (PENDING_DELIVERY, DELIVERY_FAILED) never expires by this index | YES | YES | S, ttl-set guard | KEEP | none |
| 36 | `customer_otp_challenges` | `otp_challenge_createdat_backstop_ttl` | createdAt ↑ | N | — | N | **1 day** | TTL backstop for null-`expiresAt` rows | YES | YES | S, ttl-set guard | KEEP | none |
| 37 | `customer_otp_verified_grants` | `expiresAt_1` (generated) | expiresAt ↑ | N | — | N | **0 s** | TTL cleanup of one-time login grants | YES | YES | S, ttl-set guard | KEEP | none |
| 38 | `customer_otp_verified_grants` | `challengeId_1` (generated) | challengeId ↑ | **Y** | — | N | — | Invariant: one grant per challenge; `findByChallengeId` | YES | YES | S, D | KEEP | none |
| 39 | `customers` | `customer_one_per_phone` | phoneNormalized ↑ | **Y** | — | N | — | Invariant: one customer per phone; upsert race guard | YES | YES | S, D, R | KEEP | none |
| 40 | `customer_sessions` | `session_by_customer` | customerId ↑ | N | — | N | — | **no query uses it** (all session reads are `_id`-keyed; `SchemaBootstrap` comment says so) | YES | YES | S | **DROP-CANDIDATE** (disabled migration `V0101`; needs owner approval) | none |
| 41 | `customer_sessions` | `session_expiry_ttl` | expiresAt ↑ | N | — | N | **0 s** | TTL cleanup; auth checks `expiresAt`/`revokedAt` itself | YES | YES | S, ttl-set guard | KEEP | none |
| 42 | `customer_addresses` | `address_by_customer_updated` | customerId ↑, updatedAt ↓, _id ↑ | N | — | N | — | `AddressRepository.findAllByCustomer` (filter + sort exact match; bounded by address limit) | YES | YES | S | KEEP | none |
| 43 | `checkout_quotes` | `checkout_quote_one_per_idempotency_key` | customerId ↑, idempotencyKeyDigest ↑ | **Y** | — | N | — | Invariant: one quote per (customer, idempotency key); `findByIdempotency`; create race guard | YES | YES | S, D, R, B (`CheckoutQuoteIT`) | KEEP | none |
| 44 | `orders` | `order_one_per_quote` | customerId ↑, quoteId ↑ | **Y** | — | N | — | Invariant: one order per (customer, quote); `findByCustomerAndQuote`; replay recovery | YES | YES | S, D, R, B (`OrderServiceIT`) | KEEP | none |
| 45 | `inventory_reservations` | `inventory_reservation_one_per_order` | orderId ↑ | **Y** | — | N | — | Invariant: one reservation per order; `findByOrderId` | YES | YES | S, D, R, B | KEEP | none |
| 46 | `inventory_reservations` | `inventory_reservation_expiry` | status ↑, expiresAt ↑ | N | — | N | — | `findExpiredBatch` (equality + range + sort: exact ESR fit) | YES | YES | S | KEEP | none |
| 47 | `memberships` | `membership_one_open_per_customer` | customerId ↑ | **Y** | `{openTerm:true}` | N | — | Invariant: at most one open term per customer; `findOpenByCustomer` | YES | YES | S, D, R, E (`MembershipRepositoryIT`) | KEEP | none |
| 48 | `memberships` | `membership_one_per_grant_reference` | grantSource ↑, grantRef ↑ | **Y** | — | N | — | Invariant: grant idempotency key; `findByGrantReference` | YES | YES | S, D | KEEP | none |
| 49 | `memberships` | `membership_active_by_customer` | customerId ↑, status ↑ | N | — | N | — | Entitlement candidate read (`$or` ACTIVE / `openTerm` exists, limit 2); bounded per-customer scan (partial index unusable by design) | YES | YES | S, E (`MembershipRepositoryIT:143-155`) | KEEP | none |
| 50 | `evidence_links` | `evidence_link_one_per_evidence_product_type` | evidence_id ↑, product_id ↑, link_type ↑ | **Y** | — | N | — | Invariant: one link per (evidence, product, link type); upsert key of `ClassifyService:58` / `PublishService:64` | NO (DB-3) | NO — migration `V0005` | S, D, R | **IMPLEMENTED (DB-3)** via migration `V0005` | duplicate preflight (runbook §9.2); BLOCKED if duplicates exist; never auto-resolved |
| 51 | `taxonomy_nodes` | `taxonomy_node_one_active_per_parent_name` | parent_id ↑, name ↑ | **Y** | `{status:"active"}` | N | — | Invariant: unique ACTIVE sibling names (`TaxonomyChangeService:222-226,329-332,397-400` app check) | NO (DB-3) | NO — migration `V0006` | S, D, R | **IMPLEMENTED (DB-3)** via migration `V0006` | duplicate preflight on active nodes (runbook §9.2); BLOCKED if duplicates exist; never auto-resolved |

The 15 other collections carry only `_id_`: `gtin_registry`, `identity_keys`, `discriminating_attributes`, `brands`, `evidence`, `campaigns`, `variant_groups`, `marketplace_crosswalks`, `system_config`, `attachment_registry`, `rollup_state`, `id_sequences`, `customer_profiles`, `customer_address_state`, `customer_carts`. Verified correct: every operation on them is `_id`-keyed (`customer_profiles`, `customer_address_state` and `customer_carts` use `_id = customerId`).

## 5. Query-path coverage by domain

No query on any in-scope collection lacks a suitable index **except** the items in §6. "SERVED" is index-shape reasoning; §3 records where a one-off, uncommitted experiment (X) observed the plan. Only three committed tests assert plans: `IndexContractIT` (stamp-worker shape), `ScaleIT` (no-sort category filter) and `MembershipRepositoryIT` (open-term and candidate queries); `ProductIndexMigrationIT` and `SnapshotIndexIT` assert index existence and key order only.

| Domain | Query paths | Verdict |
|---|---|---|
| **Orders** | `(customerId, quoteId)` replay/recovery (`OrderRepository:39-45,61-63`); `{_id, customerId}` get (`:52-55`); insert only | SERVED. **No customer/order-history or status/date query exists in main** (controller exposes POST and GET `/{orderId}` only); none is indexed and none is proposed |
| **Checkout** | `(customerId, idempotencyKeyDigest)` (`CheckoutQuoteRepository:56-62`); `{_id, customerId}` | SERVED. No quote-list query exists. Expiry is a clock check (410), not a TTL: expired quotes must remain readable |
| **Membership** | grant ref; `{customerId, openTerm:true}` (partial implied); candidate `$or` read limit 2 | SERVED; explain asserted by `MembershipRepositoryIT:136-155`. The candidate scan is bounded per customer (partial index unusable by design) |
| **Auth** | `findActive`/`findDelivering` carry the partial markers literally; grants by `challengeId`; sessions all `_id`-keyed (`isActive` is the hottest query: a point read) | SERVED. `session_by_customer` is unused |
| **Customer** | profile/state/cart: `_id` only; addresses: `{customerId}` + sort `updatedAt desc, _id` | SERVED |
| **Catalogue** | list/cursor, visibility probe, bundle repoint, taxonomy sibling/children, snapshot walks, definitions/schemas by `(key|schema_id, version)`, lease claims | SERVED, with the per-vertical scan fixed in DB-2 (§3) |
| **Inventory** | point + `$in` batch on `(sku_id, fulfillment_location_id)`; reservation by `orderId`; expiry batch | SERVED |
| **Pricing** | point/batch `price_current`; ledger insert; rollup select/purge | SERVED (§3) |
| **Audit** | `product_events`/`node_events`/`domain_events`: **no reader in main**. Audit-read (PR #49) adds the first readers | see §9 |

## 6. Gap analysis

### 6.1 Missing indexes
| Gap | Evidence | Decision |
|---|---|---|
| Per-vertical `_id`-ordered scan | one-off experiment §3 (X) | **IMPLEMENTED**: `product_vertical_id_cursor` |
| `evidence_links` taint paging `{evidence_id, active, _id}` | one-off experiment §3 (X): sort over ≤ 60 docs / `_id` walk | not proven → **not implemented** |
| `aliases.node_id` (mergeNodes `updateMany`, `TaxonomyChangeService:303`) | 25 seeded rows, admin-only, in-transaction | not proven → no action; revisit if aliases grow |
| `catalogue_releases` `status=publishing` (`ReleaseGate:28-29`) | partial `gate` index unusable; tiny collection, runs per change transaction | no action |
| `attribute_definitions` whole-set scan per consumer page (`ConsumerProjectionService:153-157`, `status=active OR absent`) | ~110 rows; an `OR`-with-absent predicate is not index-friendly | no index; caching is an application concern |
| `attribute_schemas`/definitions `release_id,status` (`TaxonomyChangeService:143-150`), `TaxonomyLoader:75` | ~110/48 rows, rare | no action |
| Reconciler whole-catalog sweep | one-off experiment §3 (X) | re-check in DB-7 |

### 6.2 Unnecessary / unused indexes (retained unless a migration drops them)
Usage was re-verified against current `main` in DB-3 (runbook §14). `session_by_customer` and `canonical_keys (product_id)` are **DROP-CANDIDATES** (disabled migrations `V0101`/`V0102`; they run only after owner approval). `classification_history (product_id, decided_at)`, `products (variant_group_id)` sparse, and the unique indexes on the unused collections `batches` and `campaign_membership` are **WAIT** (a decision on a future reader or on retiring the collection). `product_events (product_id, at)`, `node_events (node_id, at)` and `domain_events (aggregate_type, aggregate_id, at)` are **WAIT** (PR #49 has merged; re-evaluate with production write data). `price_events (product_id, ts)` is **RETAIN (R1)**. **No index is dropped by default**; each is a recorded write-amplification cost with no reader in main, and `product_events` is the highest-write ledger.

## 7. Uniqueness

### 7.1 Invariants already enforced by a Mongo unique index
`customers (phoneNormalized)`; OTP one-delivering and one-active (partial); one grant per challenge; `checkout_quotes (customerId, idempotencyKeyDigest)`; `orders (customerId, quoteId)`; `inventory_reservations (orderId)`; `memberships` one-open (partial) and one-per-grant-reference; `price_current`, `inventory`, `media_refs`, `service_areas`, `product_card_base`, `offers_current`, `aliases`, `attribute_definitions`, `attribute_schemas`, `consumer_projection_policy`, `taxonomy_snapshot_nodes`, `price_rollups`, `catalogue_releases (gate)` (partial). Each has DB-level duplicate-rejection assertions in `IndexContractIT`; the high-contention ones (`customers`, `orders`, `checkout_quotes`, `inventory_reservations`, `price_current`, `catalogue_releases`, OTP delivering, memberships) also have concurrent-race assertions.

### 7.2 Invariants that rely on a unique `_id` (no extra index needed)
`customer_profiles`, `customer_carts`, `customer_address_state` (`_id = customerId`); `work_queue` deterministic ids (`card_rebuild:<sku>` etc.); `gtin_registry`, `identity_keys`, `canonical_keys`, `evidence`, `id_sequences`, `system_config`.

### 7.3 Invariants enforced only by application logic — unique indexes (the first two implemented in DB-3 as preflight-gated migrations; the others remain proposed)

| Business invariant | Proposed key | Scope / partial filter | Duplicate behaviour today | Migration / preflight requirement | Concurrency impact |
|---|---|---|---|---|---|
| **One link per `(evidence, product, link_type)`** (`ClassifyService:58`, `PublishService:64` upsert on this filter) | unique `{evidence_id:1, product_id:1, link_type:1}` | none | no unique index; concurrent upserts of an absent key insert two documents. Safe today only *accidentally*: both writers CAS the same `products` document in the same transaction, which forces a retry. Any writer without that CAS (script, test insert `TaintCrashIT:30`) can duplicate. Duplicates are benign downstream (deterministic `attr_reval` ids collapse them) | **Required preflight:** `aggregate([{$group:{_id:{evidence_id:"$evidence_id",product_id:"$product_id",link_type:"$link_type"},n:{$sum:1},ids:{$push:"$_id"}}},{$match:{n:{$gt:1}}}])` must return 0 rows in every persistent environment; else a cleanup migration (keep the earliest `_id`). **Must not** be created by startup bootstrap: a failing unique build would abort startup on every instance (R5) | after creation the DB, not an incidental CAS, closes the race; duplicate inserts fail with 11000 (writers must treat it as "already linked") |
| **Sibling names unique among active nodes** (`TaxonomyChangeService:222-226,329-332,397-400`, `countDocuments` check) | unique partial `{parent_id:1, name:1}` | partial `{status:"active"}` | app check only; race closed only because every change transaction first `$inc`s `change_seq` on the open release (`ReleaseGate:34-43`), so writers that bypass the gate (seed loader, manual operations) are unprotected | **Preflight:** group active nodes by `(parent_id, name)` having `n>1` must be empty. **Seed evidence:** the v0.9.0 seed (460 active nodes) has **0** duplicates, also after case/trim normalisation. Live data UNVERIFIED. Create via DB-3 migration only; decide case-sensitivity (current code compares exact names) | serialisation by the release fence already exists; the index adds defence in depth |
| **Ledger row unique per `(sku_id, version)`** for paise price events | unique partial `{sku_id:1, version:1}` | partial on the paise-row marker (needs the R1 discriminator first) | duplicate ledger rows are prevented only because a CAS miss rolls the whole transaction back (`PricingService:161-165`) | belongs to the **R1 work package** (needs explicit event discriminator); no live paise writer exists yet, so the preflight is trivially empty today | n/a today |
| One open `classification_review` per product | — | — | none: each classify-to-holding/mint inserts a new row with a generated `_id` (`MintService:115-117`, `ClassifyService:66-68`) | **No invariant is defined**; a business decision is required before any index | — |

**DB-3:** the first two rows are now migrations `V0005` and `V0006` (runbook §9.2). They create the index only after a read-only duplicate preflight returns nothing; otherwise the migration is `BLOCKED`, the index is not created, and business data is never deleted or merged. They are created by the migration job, never by startup bootstrap. The existing unique indexes are unchanged; if live data already violated one, startup would already fail today.

## 8. TTL

TTL is permitted only for temporary data. The repository has **exactly four** TTL indexes, all on auth/OTP collections; `IndexContractIT` pins this set and **fails if any other index (in particular on a durable collection) carries a TTL**.

| TTL index | Field | Duration | Docs carrying the field | Effect of expiry | Application behaviour after expiry | Temporary per contract? |
|---|---|---|---|---|---|---|
| `customer_otp_challenges.expiresAt_1` | `expiresAt` | 0 s | only activated challenges; **null on PENDING_DELIVERY/DELIVERY_FAILED, which Mongo's TTL monitor never expires** | doc deleted ≈ 60 s after `expiresAt` | `verify` on a swept challenge → generic INVALID (the distinct EXPIRED outcome is lost); the app enforces expiry itself (`OtpService:223-225,272-278`); rate limiting is Redis, not these docs | yes (ESD: "never relying solely on the async TTL sweep") |
| `customer_otp_challenges.otp_challenge_createdat_backstop_ttl` | `createdAt` | 1 day | always set | sweeps stuck null-`expiresAt` rows | none depends on them | yes (cleanup only) |
| `customer_otp_verified_grants.expiresAt_1` | `expiresAt` | 0 s | always set | grant deleted | consume then returns null = INVALID, same as expired; same-transaction idempotency does not need the doc afterwards | yes (one-time, minutes-long grant) |
| `customer_sessions.session_expiry_ttl` | `expiresAt` | 0 s (session lifetime 30 days default) | always set | session deleted at `expiresAt` | auth checks `expiresAt`/`revokedAt` as predicates, so TTL is cleanup only; refresh on a swept session → INVALID | yes, **but** revoked sessions linger until `expiresAt` and the revoked-session audit trail disappears at expiry — if session forensics are ever required, this TTL deletes them |

**Deliberately no TTL** (durable or required to remain readable): `orders`, `checkout_quotes` (expired quotes must still resolve for replay and answer 410), `memberships`, `inventory_reservations`, `customer_carts`, `customer_profiles`, `customer_addresses`, `price_current`, **`price_events`** (R1), `product_events`, `node_events`, `domain_events`, `classification_history`, `products`, `inventory`. **No TTL change is proposed.**

## 9. R1 — pricing index impact (analysis only; retention semantics unchanged)

- `price_events` writers are insert-only (`PricingService:139`, `OffersService:35`); the only update is rollup setting `rolled:true`; the only delete is purge (`RollupService:68`). Nothing in main reads it except `RollupService`.
- **One-off explain experiment (§3, X; uncommitted, not reproduced by this PR):** `rolled_1_ts_1` serves both the select (`rolled != true`, 5,001 keys for 5,000 rows) and the purge. The index is not the problem; the unbounded in-memory read (`RollupService:46-48`) and the missing shape discriminator are (DB-1 §9). Current behaviour remains **non-compliant with R1**; DB-2 does not change it.
- `{rolled, ts}` has ~2 distinct leading values (absent/null vs true): a partition flag, not a selective key; every insert pays for it and every rollup moves the key.
- `(product_id, ts)` has no reader; paise rows set `product_id = sku_id`, so it is the natural index for a future per-SKU history read → **RETAIN**.
- **Future needs (not implemented):** once an explicit event discriminator exists, a rollup/purge that must exclude durable ledger rows needs only a residual filter on it (no new index), or `{kind, rolled, ts}` if fully index-served selection is wanted; a per-SKU ledger read wants `{kind, product_id, ts}`; a ledger integrity guard is the partial unique `(sku_id, version)` in §7.3. A partial index on `rolled:{$exists:false}` would **not** serve the current `rolled != true` predicate (the planner cannot prove implication).
- Retention changes (stopping the purge, discriminator) belong to the R1 work package; DB-2 only records the supporting index needs.

## 10. Audit-Read (PR #49) index requirements — MERGED, pinned, migration `V0007`

PR #49 adds, on each of `product_events`, `node_events` and `domain_events`, three **partial** indexes (predicate `{actor:{$type:"object"}}`, i.e. attributed rows only; unattributed historical rows are never returned): `audit_read_recent {at:-1,_id:-1}`, `audit_read_actor {actor.id:1,at:-1,_id:-1}`, `audit_read_request {actor.request_id:1,at:-1,_id:-1}` — **9 indexes**. Every audit query carries the partial predicate, so the planner may use them. These nine are now pinned exactly in `IndexContractIT`'s independent oracle and in `IndexCatalog.AUDIT_READ_SPECS`, and are created for migrated databases by **migration `V0007`** (the legacy `bootstrap()` also creates them for test/dev).

| Filter combination | Served by | Verdict |
|---|---|---|
| none / limit / cursor / time range | `audit_read_recent` | SERVED (explain asserted by PR #49's `AuditReadIndexIT` for default, cursor) |
| `requestId` | `audit_read_request` | SERVED (point lookup) |
| `actorId` (± range/action/type) | `audit_read_actor` | SERVED, in page order |
| `actorType` only | none specific → `audit_read_recent` walk + fetch filter | **SCAN RISK** (rare type scans the attributed ledger until `limit+1` or the 5 s `maxTime`) |
| `action` only | same | **SCAN RISK** |
| `targetType` only | product/node: no field filter at all → recent walk; domain_events: `aggregate_type` equality → legacy index + SORT or recent walk | probably OK / **SORT RISK** on domain_events |
| `targetType + targetId` | legacy entity indexes serve the equality but lack `_id`, so `(at,_id)` sort needs an in-memory SORT or falls back to the recent walk | **NOT in the index design**, untested |

Requirements/recommendations for PR #49 (not changed by DB-2): (1) either add per-ledger partial indexes for target lookups (`{<target field>:1, at:-1, _id:-1}` with the same partial predicate; `domain_events` `{aggregate_type:1, aggregate_id:1, at:-1, _id:-1}`) or document `targetId` as a best-effort bounded query; (2) `actorType`-only and `action`-only are low-cardinality — do **not** index; require another selective filter or a time range, or accept the 5 s/100-row bound explicitly; (3) document that `classification_history` and `price_events` are not audit sources; (4) note write amplification: 3 extra index entries on every attributed write, on the highest-write ledger (`product_events`), and the request-id index also indexes attributed rows without a `request_id` as a null key. Volume data is UNVERIFIED.

**DB-0/DB-1 statements that become stale when PR #49 merges:** "no reader" for `product_events`, `node_events`, `domain_events` (DB-0 §8, §12, R14; DB-1 §7.2 notes); `classification_history` stays unread. Nothing in PR #49 changes `price_events`, rollup/purge or R1. These documents should be refreshed (affected facts only) when it merges.

**Evidence added in DB-4 for the unindexed audit filters (one-off, uncommitted local measurement on a real MongoDB 7 container; 40,000 attributed rows per ledger; not staging evidence):** a filter that matches nothing (`action=<unknown>`, `actorType=SYSTEM`) walks `audit_read_recent` and examines all 40,000 keys and 40,000 documents per ledger (127 ms for the three ledgers); `targetType=product&targetId=…` uses the legacy `product_id_1_at_1` index plus an in-memory sort of that product's events (80 keys for 51 returned), `taxonomy_node` likewise via `node_id_1_at_1` (800 keys). The cost of a selective unindexed filter is therefore linear in ledger size, bounded by the 5 s `maxTime`; `TZP-SYSTEM` is an unbounded pseudo-target. **Decision state: PENDING STAGING EVIDENCE** (keep / add an index / map the timeout to a controlled 503) — `DATABASE_STAGING_RUNBOOK.md` §9.2. No index is added speculatively.

## 10c. Address-create idempotency indexes — migration `V0015`, migration-only

| Collection | Index | Keys | Why |
|---|---|---|---|
| `customer_address_idempotency` | `address_idempotency_expiry_ttl` | `expire_at` ↑, `expireAfterSeconds: 0` | a row only has to outlive one client retry window; it is never the record of the address |
| `customer_address_idempotency` | `address_idempotency_by_customer` | `customer_id` ↑ | account erasure deletes every row of one customer |

Lookups by the replaying request use `_id` (`<customerId>|<sha256(key)>`). `bootstrap` never creates these indexes.

## 11. Migration / preflight requirements (summary)

| Item | Requirement | Phase |
|---|---|---|
| `product_vertical_id_cursor` (implemented) | **moved to migration `V0002` in DB-3**: it no longer runs in the startup runner, so a build on a large `products` collection cannot delay application readiness, and the four live states (absent / exact / same keys under another name / conflicting options) are handled deterministically — never `IndexOptionsConflict` (runbook §9.1). Build time on production-sized data is still UNVERIFIED: measure on staging first; run the job in a quiet window. **Preflight:** `listIndexes` on `products` for the keys `{classification.vertical_id:1,_id:1}` | DB-3 (done) / DB-4 |
| Unique `evidence_links (evidence_id, product_id, link_type)` | implemented as migration `V0005`; duplicate preflight (§7.3) in every persistent environment; `BLOCKED` if duplicates exist | DB-3 (done) |
| Unique partial `taxonomy_nodes (parent_id, name)` | implemented as migration `V0006`; duplicate preflight on active nodes; decide case-sensitivity before changing it | DB-3 (done) |
| Unique partial `price_events (sku_id, version)` | R1 discriminator first | R1 package |
| Drop unused indexes (§6.2) | disabled drop migrations `V0101`/`V0102` for the two DROP-CANDIDATES; the rest WAIT (runbook §14) | owner approval |
| Explicit names for the *(generated)* indexes | `ReplaceIndexMigration` exists (a redefinition is create-before-drop; a pure rename is drop-then-create with a short window); no rename is scheduled | on demand |
| Any unique index creation | only migrations behind a duplicate preflight; `bootstrap` creates only the baseline indexes that already existed | DB-3 (R5, done) |
| Audit-read nine indexes | DONE: pinned in `IndexContractIT`, created by `V0007` | done |

## 12. Test coverage

Before DB-2, only memberships, `products` PAG-2 (migration/explain), `taxonomy_snapshot_nodes` traversal, and the unique-only checks on `price_current`, `inventory`, `media_refs`, `service_areas`, `product_card_base` asserted any index; no TTL index and no partial OTP/release index was asserted.

`IndexContractIT` (new, runs on Testcontainers `mongo:7`) now asserts: exact spec of all 60 manifest indexes (the executable `IndexCatalog` is also pinned equal to this independent oracle, and to what `bootstrap` creates, plus nothing migration-managed except the nine audit-read indexes that the legacy bootstrap also creates); no extra index on any collection (closed set); **only the four TTL indexes exist and none on a durable collection**; the superseded 3-key `products` index is absent while PAG-2 is present; idempotent re-bootstrap for the whole set; DB-level duplicate rejection for 21 unique indexes; partial-index semantics for OTP, the one-open-membership slot (distinct grant references, so only the partial index can reject) the release gate and the taxonomy active-sibling index; concurrent duplicate races for 10 invariants (the membership race uses distinct grant references so it exercises the partial one-open index and not the grant-reference index); and that the **stamp-worker query shape** (vertical equality, `_id` cursor, `_id` sort, `_id`-only projection) is served by `product_vertical_id_cursor` with no SORT, no COLLSCAN and no `_id` walk. The no-projection canonical-key backfill shape is **not** asserted, and the test does not assert a covered (`PROJECTION_COVERED`) plan; "covered" is reasoning, not a tested property. Separately, a **one-off, uncommitted, local mutation check** (removing the new index; adding a TTL to `orders`; shortening a session TTL; removing the membership one-open index) failed the intended tests; that check is **not reproduced by this PR** and the PR contains no mutation test.

Remaining gaps (DB-7): explain assertions for the other query paths (consumer list at large scope, reconciler, audit-read matrix), plan stability across MongoDB versions, a multi-node replica set, a real-server TTL deletion test, and skewed-data plan checks.

## 13. Implemented in DB-2 and DB-3

- DB-2: `product_vertical_id_cursor` on `products` `{classification.vertical_id:1, _id:1}`; `IndexContractIT`; this manifest.
- DB-3: the cursor index moved from startup bootstrap to migration `V0002`; unique indexes `V0005` (`evidence_links`) and `V0006` (`taxonomy_nodes`, partial) as preflight-gated migrations; disabled drop migrations `V0101`/`V0102`; the migration framework (`docs/database/DATABASE_MIGRATION_RUNBOOK.md`).

**Not implemented, by policy:** index drops (disabled, need approval), any TTL change, any R1 retention change, the `evidence_links` paging index (not proven), the paise-ledger `(sku_id, version)` unique index (needs the R1 discriminator). (The nine audit-read indexes of PR #49 are implemented: migration `V0007`, pinned exactly in `IndexContractIT`, §10.)

## 14. Live-environment unknowns

Whether any live collection violates a proposed unique key; live `products` size and index build time; actual live index set and names (drift); effective privileges for `createIndex`/`dropIndex`; plan behaviour on the production MongoDB version and data skew. (PR #49 merged as `8227286`.)

*DB-2 changes no data and makes no connection to any live MongoDB.*
