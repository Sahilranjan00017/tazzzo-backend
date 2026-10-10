# FINAL_DATABASE_AUDIT — Phase 10

Final database audit of `services/catalog-service`. **Every number below was recalculated** from the code and from a real MongoDB 7 database migrated from an
empty state through the registered migrations (the path the application's migration job uses); none was copied from an earlier document. Where an earlier
document disagreed, it was corrected (§11).

| Item | Value |
|---|---|
| Base | `origin/main` `d3da14d91b990ffa9faa9b1b80a334b725d874d2` ("Observability: import/media/projection/queue metrics ...", #118) |
| Branch | `chore/final-db-audit`: this audit plus one separate code commit, migration `V0019` (finding F-1) |
| Server | MongoDB **7.0.43** (Testcontainers `mongo:7`, single-node replica set, no authentication), disposable containers only |
| Runtime | OpenJDK 21.0.12 (Ubuntu build), `./mvnw`, Spring Boot 4.1.1 (the `pom.xml`; `CLAUDE.md` already says 4.1.x since #113) |
| Method | throwaway JUnit harnesses (not committed; §13) that run the real `MigrationRunner` with `Migrations.defaults(...)` against fresh scratch databases, then introspect `listCollections`, `listIndexes`, `schema_migrations`; a second harness bulk-inserts a 100,000-product dataset (§6) straight into the migrated database (not through the API) and runs `explain("executionStats")` |
| Not touched | no AWS, no Atlas, no production, no secrets; no existing worktree; no force push; no PR opened; no merge |

## 1. Headline counts

| Quantity | `main` `d3da14d` | This branch (with `V0019`) | How derived |
|---|---|---|---|
| **Application collections** | **57** | **57** | `listCollectionNames()` of a database migrated from empty, minus the two runner collections. Equals `SchemaBootstrap.COLLECTIONS.size()` (57) in both directions (none missing, none extra). `DatastorePrivilegeIT` independently pins `roster + bookkeeping` |
| Physical collections | 59 | 59 | the 57 plus `schema_migrations` and `schema_migration_lock` (each has only the `_id_` index) |
| created by baseline `V0001` | 49 | 49 | an empty database with only `V0001` applied |
| created by later migrations | 8 | 8 | `delivery_slot_windows`, `delivery_slot_usage` (`V0008`); `support_cases` (`V0011`); `content_blocks` (`V0013`); `notification_outbox` (`V0014`); `customer_address_idempotency` (`V0015`); `import_jobs`, `import_rows` (`V0016`). `V0001`'s roster is the frozen `BaselineV0001Contract` (49), not the live roster, so each of these appears when its own migration creates its first index |
| **Registered migrations** | **20** | **21** | `Migrations.defaults(...)`: 19 enabled by default + 2 disabled drop candidates (`V0101`, `V0102`); on `main` 18 + 2 |
| Recorded `APPLIED` in `schema_migrations` after a full default run | 18 | 19 | `schema_migrations` read back; `V0101`/`V0102` are not run unless named in `enabled-migrations` |
| Kinds (enabled) | SCHEMA 16, REFERENCE_INIT 1 (`V0003`), DATA 1 (`V0004`) | SCHEMA 17, REFERENCE_INIT 1, DATA 1 | `Migration.kind()`; the two disabled ones are SCHEMA |
| **Indexes, non-`_id`** | **82** | **83** | `listIndexes()` on every application collection of the migrated database, `_id_` excluded: **48** from `V0001` + **34 / 35** migration-only |
| Indexes including `_id_` | 139 | 140 | 83 + 57 `_id_` (142 physical including the two runner collections) |
| unique / TTL / partial / sparse | 29 / 7 / 18 / 2 | 29 / 7 / 19 / 2 | the same listing; no index has a collation or is `hidden` |
| **Validators** | **1** | **1** | `listCollections` options of all 59 collections: only `products` carries a `$jsonSchema` (`validationLevel: strict`, `validationAction: error`) |
| Seed reference data | 460 nodes, 25 aliases, 110 definitions, 48 schemas | same | `V0003` note on the migrated database |

(The legacy `SchemaBootstrap.bootstrap()` path used by dev/test creates the same 57 collections and **57** indexes: the 48 baseline plus the 9 audit-read ones; the
migration-only indexes are by design never created by it.)

## 2. Collections

* **Created by baseline bootstrap/`V0001` (49):** every collection of `SchemaBootstrap.COLLECTIONS` except the eight below. `products` is created with the strict validator (§5).
* **Created by later migrations (8):** `delivery_slot_windows`, `delivery_slot_usage` (`V0008`), `support_cases` (`V0011`), `content_blocks` (`V0013`), `notification_outbox` (`V0014`), `customer_address_idempotency` (`V0015`), `import_jobs`, `import_rows` (`V0016`). The collection appears when the migration creates its index; there is no separate "create collection" step.
* **Runner bookkeeping (2, not application collections):** `schema_migrations` (the history), `schema_migration_lock` (the single-runner lease).
* **Fifteen application collections have only `_id_`** and are only ever read or written by `_id`: `gtin_registry`, `identity_keys`, `discriminating_attributes`, `brands`, `evidence`, `campaigns`, `variant_groups`, `marketplace_crosswalks`, `system_config`, `attachment_registry`, `rollup_state`, `id_sequences`, `customer_profiles`, `customer_address_state`, `customer_carts` (the three customer ones are keyed `_id = customerId`). This matches `DATABASE_INDEX_MANIFEST.md` §4.
* `app_config` is **not** a collection: the operational app config is the document `_id: "app_config"` in `system_config`.

## 3. Migrations

### 3.1 Registry, ordering, checksums

| Id | Kind | Default | On an empty database | +collections | +indexes | Definition SHA-256 (as pinned in `MigrationRegistryTest`; recomputed equal) | Stored `schema_migrations.checksum` (recomputed equal) |
|---|---|---|---|---|---|---|---|
| `V0001__baseline_schema` | SCHEMA | on | APPLIED_NOW | 49 | 48 | `6e872b65f7d9186038025b5906a1be7841becc7fec1a3adb43589c83216fd9e9` | `3b703e4a08a7ad8f7a400323631587675238e6db6db48c3f06826ae6f0a695cb` |
| `V0002__products_vertical_id_cursor_index` | SCHEMA | on | APPLIED_NOW | 0 | 1 | `b719f7b909fc5dcf25a15344fa1112082d5b76b658d50950381787eeaa6f0e4a` | `8ed8ef76295ba8ec42a74b9fe903014edc8857a73bd5a226102a62893cee3614` |
| `V0003__taxonomy_seed_0_9_0` | REFERENCE_INIT | on | APPLIED_NOW | 0 | 0 | `a687c64f837f5e5b020875b74a5fa55354e25f5733b4089a3350afadf2e36931` | `bfd38800b687c074e7caf9c60689f4542fa24ba26ef02e6b291abe299186ef5a` |
| `V0004__seed_schemas_pack_fields_not_required` | DATA | on | ADOPTED | 0 | 0 | `a31446fe65f7f3d4eb0f083e86a5abb0183d9dc68613ecb055e1b54d87e40e99` | `0d0b72471d1c7406e65f53c3c237a0bfa7223f025012d126abf3e67855aa16be` |
| `V0005__evidence_links_unique_link` | SCHEMA | on | APPLIED_NOW | 0 | 1 | `6c9f4fb5ede31c4e1e38b650f6d7ddb62d8d0918da62ad46d6c5d2aed6768ec2` | `e7d1bbdd8ac42967e0b7f174b3493107fe078dad54d3db51298557a4f903de8c` |
| `V0006__taxonomy_nodes_unique_active_sibling_name` | SCHEMA | on | APPLIED_NOW | 0 | 1 | `3e944ae4e2a17733dbb262d3aadb8140fe533917e82793e986516e1cc9c8ec4f` | `e2baf70afc4571c02af5d6fab96dc70df4d144ea5bd4c288cb2a5f79bb3c06fb` |
| `V0007__audit_read_partial_indexes` | SCHEMA | on | APPLIED_NOW | 0 | 9 | `3bbe2d9a67dfb44ed348f43a87b389cfa18598005879763c542b13dbe547f43d` | `109823524ab07e28e4afdafd43f591ebe649f1481e553c35ded7c419748e7146` |
| `V0008__delivery_slot_indexes` | SCHEMA | on | APPLIED_NOW | 2 | 2 | `b55296f6ecd22b9c7adc2df045cf0d5698763b511fd8e2a22f75a91a43fa9718` | `8273f0431e5bcaf7f3a38372e505c57825a5e94c6ed41f771cf2790967adc38e` |
| `V0009__product_card_search_tokens_index` | SCHEMA | on | APPLIED_NOW | 0 | 1 | `01fa69cedbb1bba63706f52504c9a61ea88d8a7c6652176dd5ffac82d27fdc38` | `57faa1507e8e211427d414cc149bd43f91cef8e325afdd78c9a3cf26ac81baec` |
| `V0010__orders_by_customer_recent_index` | SCHEMA | on | APPLIED_NOW | 0 | 1 | `f06415f5a51e692b0acc154d0ae64faa66dd6c1f0f8d66350ef20e137512079a` | `8a8a65033caf65940620dbed9f3542374c6c92481b4bba00c0f9d5085ad91c9b` |
| `V0011__support_case_indexes` | SCHEMA | on | APPLIED_NOW | 1 | 3 | `8583b8f1a4f01a72d9405e82acbe3fde53af0145c9becc373414b3ad91d666d4` | `b867d88c275babc9b49dbf1015ef285f7bba1cb0f5930110e2c8cfc58a4819e4` |
| `V0012__orders_staff_queue_indexes` | SCHEMA | on | APPLIED_NOW | 0 | 2 | `d477eefcc64c2d6fb50cb37a09d662fbeed31ca45ee3704692c0bb208dfdb23f` | `12b8ba5a9f757b391055adc8b1ab0400c13937ed2c6809a59ebf9acd0ef91dcc` |
| `V0013__content_blocks_index` | SCHEMA | on | APPLIED_NOW | 1 | 1 | `02a290d4061230c67507f9b79f2a7542fd2683d5eaf979cbd48aed879c861df0` | `d3816a305eeeda5cb48e470be42c5db98874635ffae0ed0e898b2a44e6c4407d` |
| `V0014__notification_outbox_indexes` | SCHEMA | on | APPLIED_NOW | 1 | 3 | `98a7a8a43f3941c052877173afcd9060e3e3427f23428a8ba4f8b1aab4cf29b8` | `13b8088dd49331ddbd47baf9197f7765d22a24e325b4c3ac89632cd5f6363c63` |
| `V0015__address_idempotency_indexes` | SCHEMA | on | APPLIED_NOW | 1 | 2 | `e20ea8f00718f510127f92313e1fb3161b8627003ad82826dfcdfc7812dbcce0` | `4b472055f9c0c7f1852270ec5dd6c31bc64ba4f71daab6156d9a4ca9d1aa2ebb` |
| `V0016__import_job_indexes` | SCHEMA | on | APPLIED_NOW | 2 | 5 | `be7ac104a2f6a2f349a90f06361ed6a7455f744e34ff594a4547c04ae84e258c` | `b18aca9a854a759a34da0519f13be088b3e64f7925da4953dae79a5ccf356e5e` |
| `V0017__products_validator_product_id_patterns` | SCHEMA | on | ADOPTED | 0 | 0 | `4e62897fc09f2f53838be124f3325a1f9121ee6cf67887946b212dfa6734ec2e` | `6014b2aaf2cc7cfb7ca9f59a701b1832011b7f6b994c26071976d91940dc10a8` |
| `V0018__work_queue_rebuild_indexes` | SCHEMA | on | APPLIED_NOW | 0 | 2 | `86922f79b46d93efab2962b3a5a0ab95c2dc365668dceb01f6d0a7afd3bc4f86` | `eb7df35a5e01966ffe596b335b68a7423b1d4cc1db97d133ed128b6162889557` |
| `V0019__price_events_legacy_unrolled_index` | SCHEMA | on | APPLIED_NOW | 0 | 1 | `ff5655c86b1ac0bbcd18f3eddad4ff513712a9a756bacc40454e47521a6278ca` | `22503ff5a949ed8adb887c5031e206f5c93881da627804d69dcc30f787c5c4a3` |
| `V0101__drop_unused_session_by_customer_index` | SCHEMA | **off** | not run (disabled) | - | - | `0ed42be55eda44aa3b60891e4e52ef394aac6c94293a253da23d5de9e18b606d` | n/a (never applied by default) |
| `V0102__drop_unused_canonical_keys_product_id_index` | SCHEMA | **off** | not run (disabled) | - | - | `f846da155273d5338fa0ac4285c3cad0478cbc0b40bfe7dca8859136f8b15e36` | n/a (never applied by default) |

* **Every checksum verified.** For each of the 21 registered migrations the definition SHA-256 recomputes to the value pinned in `MigrationRegistryTest.RELEASED` (kind, default flag and hash all equal). For each of the 19 migrations recorded by a real run, the stored `schema_migrations.checksum` recomputes to `SHA-256(id \n kind \n collections \n definition)` and equals `Migration.checksum()`. **21/21 pins and 19/19 stored checksums match.** (The pin hashes the definition alone; the history hashes id, kind, collection list and definition. Both were checked.)
* **Ordering:** ids are unique, match `^V\d{4}__[a-z0-9_]+$`, are sorted lexicographically and strictly ascending by number; the runner sorts its registry by id, so input order does not matter (`MigrationRegistryTest`).
* **Idempotency:** a second full `apply` on the migrated database reports all 19 `ALREADY_APPLIED`, mutates nothing (the index snapshot of every collection is byte-identical before and after), and `verify` reports `ok` with nothing pending. A dry run on the migrated database is all `ALREADY_APPLIED`.
* **Tamper refusal:** (a) a registry in which `V0002`'s definition is altered (what an in-place edit of a released migration looks like): `apply` returns `CHECKSUM_MISMATCH` (exit code 6), `V0001` is `ALREADY_APPLIED`, `V0002` is `CHECKSUM_MISMATCH`, all later steps `NOT_RUN`, nothing changed; `verify` reports "applied definition differs from the current one". (b) the stored checksum of `V0009` overwritten with `deadbeef`: `apply` returns `CHECKSUM_MISMATCH` (exit 6).
* **Dry run on an empty database is read-only:** outcome `OK`, 17 of 19 steps `WOULD_APPLY` (`V0004` and `V0017` report `WOULD_ADOPT`), and **0 collections exist afterwards** (no history collection, no lock).
* **Adoption on a fresh database:** two migrations are recorded `adopted` because their target state already holds: `V0004` (the seed is applied in its ratified shape at insert time) and `V0017` (`V0001` creates `products` with the tightened patterns already). Both are no-ops there; on a legacy database they do real work (§9).

### 3.2 Per-migration effect on an empty database (collections / indexes added, cumulative)

`V0001` 49 collections + 48 indexes; `V0002` +1; `V0003` +0 (data); `V0004` +0; `V0005` +1; `V0006` +1; `V0007` +9; `V0008` +2 collections +2; `V0009` +1; `V0010` +1; `V0011` +1 collection +3; `V0012` +2; `V0013` +1 collection +1; `V0014` +1 collection +3; `V0015` +1 collection +2; `V0016` +2 collections +5; `V0017` +0; `V0018` +2; `V0019` +1. Total 57 collections, 83 indexes.

## 4. Indexes

### 4.1 Totals and drift

83 non-`_id` indexes (48 baseline, 35 migration-only), 140 with `_id_`. **Drift against the executable `IndexCatalog`: none.** `IndexCatalog.all()` holds 83 specs (`BASELINE` 48 + `MANAGED` 35); every live index matches exactly one spec (by name; by key pattern for the default-named ones) and every spec exists live: 0 missing, 0 extra, 0 renamed. `IndexContractIT` keeps an independent oracle and passes. Against the **documents**, drift was found and fixed (§11): the manifest said "exactly four TTL indexes" (it is seven), the runbook pinned "51 indexes", and several documents carried collection counts of 49, 50, 51 and 55.

Per collection:

| Collection | non-`_id` indexes | of which migration-only |
|---|---|---|
| `aliases` | 1 | 0 |
| `attribute_definitions` | 1 | 0 |
| `attribute_schemas` | 1 | 0 |
| `batches` | 1 | 0 |
| `campaign_membership` | 1 | 0 |
| `canonical_keys` | 1 | 0 |
| `catalogue_releases` | 1 | 0 |
| `checkout_quotes` | 1 | 0 |
| `classification_history` | 1 | 0 |
| `consumer_projection_policy` | 1 | 0 |
| `content_blocks` | 1 | 1 |
| `customer_address_idempotency` | 2 | 2 |
| `customer_addresses` | 1 | 0 |
| `customer_otp_challenges` | 4 | 0 |
| `customer_otp_verified_grants` | 2 | 0 |
| `customer_sessions` | 2 | 0 |
| `customers` | 1 | 0 |
| `delivery_slot_usage` | 1 | 1 |
| `delivery_slot_windows` | 1 | 1 |
| `domain_events` | 4 | 3 |
| `evidence_links` | 3 | 1 |
| `import_jobs` | 2 | 2 |
| `import_rows` | 3 | 3 |
| `inventory` | 1 | 0 |
| `inventory_reservations` | 2 | 0 |
| `media_refs` | 1 | 0 |
| `memberships` | 3 | 0 |
| `node_events` | 4 | 3 |
| `notification_outbox` | 3 | 3 |
| `offers_current` | 1 | 0 |
| `orders` | 4 | 3 |
| `price_current` | 1 | 0 |
| `price_events` | 3 | 1 |
| `price_rollups` | 1 | 0 |
| `product_card_base` | 2 | 1 |
| `product_events` | 4 | 3 |
| `products` | 4 | 1 |
| `service_areas` | 1 | 0 |
| `support_cases` | 3 | 3 |
| `taxonomy_nodes` | 3 | 1 |
| `taxonomy_snapshot_nodes` | 2 | 0 |
| `work_queue` | 3 | 2 |

### 4.2 Unique indexes (29)

| Collection | Index | Keys | Partial filter |
|---|---|---|---|
| `aliases` | `alias_norm_1_lang_1_region_1` | alias_norm ↑, lang ↑, region ↑ | - |
| `attribute_definitions` | `key_1_version_1` | key ↑, version ↑ | - |
| `attribute_schemas` | `schema_id_1_version_1` | schema_id ↑, version ↑ | - |
| `batches` | `product_id_1_lot_no_1` | product_id ↑, lot_no ↑ | - |
| `campaign_membership` | `campaign_id_1_product_id_1` | campaign_id ↑, product_id ↑ | - |
| `catalogue_releases` | `gate_1` | gate ↑ | `{"gate": "OPEN"}` |
| `checkout_quotes` | `checkout_quote_one_per_idempotency_key` | customerId ↑, idempotencyKeyDigest ↑ | - |
| `consumer_projection_policy` | `vertical_id_1` | vertical_id ↑ | - |
| `customer_otp_challenges` | `otp_one_delivering_per_phone` | phoneNormalized ↑, purpose ↑ | `{"delivering": true}` |
| `customer_otp_challenges` | `otp_one_active_per_phone` | phoneNormalized ↑, purpose ↑ | `{"active": true}` |
| `customer_otp_verified_grants` | `challengeId_1` | challengeId ↑ | - |
| `customers` | `customer_one_per_phone` | phoneNormalized ↑ | - |
| `evidence_links` | `evidence_link_one_per_evidence_product_type` | evidence_id ↑, product_id ↑, link_type ↑ | - |
| `import_rows` | `import_rows_by_job_row` | job_id ↑, row ↑ | - |
| `import_rows` | `import_rows_one_per_product` | job_id ↑, dedup_key ↑ | `{"dedup_key": {"$exists": true}}` |
| `import_rows` | `import_rows_one_per_identity` | job_id ↑, identity_keys ↑ | `{"identity_keys": {"$exists": true}}` |
| `inventory` | `sku_id_1_fulfillment_location_id_1` | sku_id ↑, fulfillment_location_id ↑ | - |
| `inventory_reservations` | `inventory_reservation_one_per_order` | orderId ↑ | - |
| `media_refs` | `owner_type_1_owner_id_1` | owner_type ↑, owner_id ↑ | - |
| `memberships` | `membership_one_open_per_customer` | customerId ↑ | `{"openTerm": true}` |
| `memberships` | `membership_one_per_grant_reference` | grantSource ↑, grantRef ↑ | - |
| `offers_current` | `product_id_1_source_1_seller_1_channel_1` | product_id ↑, source ↑, seller ↑, channel ↑ | - |
| `orders` | `order_one_per_quote` | customerId ↑, quoteId ↑ | - |
| `price_current` | `sku_id_1_currency_1` | sku_id ↑, currency ↑ | - |
| `price_rollups` | `product_id_1_seller_1` | product_id ↑, seller ↑ | - |
| `product_card_base` | `sku_id_1` | sku_id ↑ | - |
| `service_areas` | `pincode_1` | pincode ↑ | - |
| `taxonomy_nodes` | `taxonomy_node_one_active_per_parent_name` | parent_id ↑, name ↑ | `{"status": "active"}` |
| `taxonomy_snapshot_nodes` | `release_id_1_node_id_1` | release_id ↑, node_id ↑ | - |

### 4.3 TTL indexes (7, on six temporary collections; none on a durable collection)

| Collection | Index | Field | expireAfterSeconds |
|---|---|---|---|
| `customer_address_idempotency` | `address_idempotency_expiry_ttl` | expire_at ↑ | 0 |
| `customer_otp_challenges` | `expiresAt_1` | expiresAt ↑ | 0 |
| `customer_otp_challenges` | `otp_challenge_createdat_backstop_ttl` | createdAt ↑ | 86400 |
| `customer_otp_verified_grants` | `expiresAt_1` | expiresAt ↑ | 0 |
| `customer_sessions` | `session_expiry_ttl` | expiresAt ↑ | 0 |
| `delivery_slot_usage` | `delivery_usage_expiry_ttl` | expire_at ↑ | 0 |
| `notification_outbox` | `notification_expiry_ttl` | expire_at ↑ | 0 |

`price_events` (the retained price ledger, R1) and every other durable collection have no TTL; `PriceHistoryRetentionIT` and `IndexContractIT` pin that.

### 4.4 Partial indexes (19)

| Collection | Index | Keys | Partial filter |
|---|---|---|---|
| `catalogue_releases` | `gate_1` | gate ↑ | `{"gate": "OPEN"}` |
| `customer_otp_challenges` | `otp_one_delivering_per_phone` | phoneNormalized ↑, purpose ↑ | `{"delivering": true}` |
| `customer_otp_challenges` | `otp_one_active_per_phone` | phoneNormalized ↑, purpose ↑ | `{"active": true}` |
| `domain_events` | `audit_read_recent` | at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `domain_events` | `audit_read_actor` | actor.id ↑, at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `domain_events` | `audit_read_request` | actor.request_id ↑, at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `import_rows` | `import_rows_one_per_product` | job_id ↑, dedup_key ↑ | `{"dedup_key": {"$exists": true}}` |
| `import_rows` | `import_rows_one_per_identity` | job_id ↑, identity_keys ↑ | `{"identity_keys": {"$exists": true}}` |
| `memberships` | `membership_one_open_per_customer` | customerId ↑ | `{"openTerm": true}` |
| `node_events` | `audit_read_recent` | at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `node_events` | `audit_read_actor` | actor.id ↑, at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `node_events` | `audit_read_request` | actor.request_id ↑, at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `price_events` | `price_events_legacy_unrolled` | rolled ↑, ts ↑, _id ↑ | `{"product_id": {"$type": "string"}, "seller": {"$type": "string"}, "price": {"$type": "int"}}` |
| `product_events` | `audit_read_recent` | at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `product_events` | `audit_read_actor` | actor.id ↑, at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `product_events` | `audit_read_request` | actor.request_id ↑, at ↓, _id ↓ | `{"actor": {"$type": "object"}}` |
| `taxonomy_nodes` | `taxonomy_node_one_active_per_parent_name` | parent_id ↑, name ↑ | `{"status": "active"}` |
| `work_queue` | `projection_rebuild_pending_by_requested` | status ↑, requested_at ↑ | `{"type": "product_card_rebuild"}` |
| `work_queue` | `projection_rebuild_leased_by_lease` | status ↑, lease_until ↑ | `{"type": "product_card_rebuild"}` |

Sparse (2): `products (bundle_contents.component_product_id)` and `products (variant_group_id)`. Collation: none. Hidden: none.

### 4.5 Required coverage, confirmed on the migrated database

| Area | Indexes present |
|---|---|
| `import_jobs` | `import_jobs_claim (status, lease_until, updated_at)`, `import_jobs_by_status_recent (status, _id desc)` (`V0016`) |
| `import_rows` (`V0016`) | `import_rows_by_job_row` unique `(job_id, row)`; `import_rows_one_per_product` partial unique `(job_id, dedup_key)`; `import_rows_one_per_identity` partial unique multikey `(job_id, identity_keys)` |
| media | `media_refs (owner_type, owner_id)` unique (the only access is the point lookup by owner) |
| content / banners | `content_blocks`: `content_by_placement_status_sort (placement, status, sort, _id)` (`V0013`) |
| orders | `order_one_per_quote` unique `(customerId, quoteId)`; `order_by_customer_recent`; `order_by_status_recent`; `order_recent` (`V0010`, `V0012`) |
| carts | `customer_carts` is keyed `_id = customerId`; every access is by `_id`, so no secondary index is needed |
| inventory | `inventory (sku_id, fulfillment_location_id)` unique; `inventory_reservations`: unique `orderId`, `(status, expiresAt)` |
| `work_queue` | `status_1_type_1`; the two `V0018` partials `projection_rebuild_pending_by_requested (status, requested_at)` and `projection_rebuild_leased_by_lease (status, lease_until)`, both `type = product_card_rebuild` |
| `notification_outbox` (`V0014`) | `notification_due (status, next_attempt_at, _id)`, `notification_by_customer (customer_id)`, `notification_expiry_ttl` |
| address idempotency (`V0015`) | `address_idempotency_expiry_ttl`, `address_idempotency_by_customer`; the lookup itself is by `_id` |
| customer | `customer_one_per_phone` unique; `session_by_customer` (unused, drop candidate `V0101`), `session_expiry_ttl`; `address_by_customer_updated`; OTP: two partial unique, two TTL on challenges, TTL + unique `challengeId` on grants; memberships: partial unique open term, unique grant reference, `(customerId, status)`; `checkout_quote_one_per_idempotency_key` unique |
| price ledger (new) | `price_events_legacy_unrolled` partial (`V0019`), finding F-1 |

## 5. Validators

**One collection has a validator: `products`** (`validationLevel: strict`, `validationAction: error`), created by `V0001` (and by `SchemaBootstrap.ensureCollections`) when the collection is new.

* **V0017 tightened product-id patterns are present on all four slots**, each exactly `^TZP-[A-Za-z0-9-]{1,40}\z`: `_id`, `bundle_contents[].component_product_id`, `pack_of.component_product_id`, `merged_into` (nullable string). `\z`, not `$`, so a trailing newline is refused.
* **Nested constraints (from the live validator):** `additionalProperties: false` on the document root, `identity`, `gtins[]`, `localized_titles`, `classification`, `bundle_contents[]`, `pack_of` and `ext`; **no** `additionalProperties` restriction on `attributes`, `attributes_meta`, `attribute_provenance` and `classification.method_detail` (open by design: governed by the attribute registry, not the validator). Enums: `product_type` (`single`, `variant_pack`, `bundle`), `lifecycle` (`draft`, `active`, `merging`, `discontinued`, `archived`, `merged`), `identity.type` (`gtin`, `internal`), `classification.status` (`confirmed`, `provisional`, `review`, `scope_blocked`). Bounds: `gtins` ≤ 12, `classification.evidence_refs` ≤ 20 each `^EV-`, `bundle_contents` ≤ 100 with `qty` int ≥ 1, `pack_of.qty` int ≥ 2, `browse_verticals` ≤ 120, `version` int ≥ 1, `classification.confidence` double or null in [0, 1]. `oneOf` by `product_type` ties the shape to the type (single: string vertical, no bundle/pack; variant_pack: requires `pack_of`; bundle: null vertical, ≥ 2 `bundle_contents`). Required: `_id, product_type, identity, brand_code, title, lifecycle, classification, attributes, attributes_meta, version, created_at`.
* **A pre-existing database** is not touched by `V0001`; `V0017` PATCHES its live validator (refusing with a sample if any document violates the grammar, never rewriting data).

### 5.1 Collections WITHOUT a validator that hold product or SKU ids

| Collection.field | Who writes it | Judgement | Validator warranted? |
|---|---|---|---|
| `inventory.sku_id` | `InventoryAdminController` (`requireProduct(skuId)`: the SKU must exist in `products`, whose `_id` the validator constrains); `InventoryKey` also trims and caps at 128 | app-level validation suffices | not now. A validator would add a second source of truth for the 128-char key rule on the busiest write collection (300,000 rows here) |
| `price_current.sku_id` | `PriceAdminController` (`requireProduct`) | suffices | not now |
| `media_refs.owner_id` | `MediaAdminController` (`requireProduct` on every route), `MediaSet` (non-blank, trimmed, ≤ 128) | suffices | not now |
| `product_card_base.sku_id` / `product_id` | the projection worker, from `products` rows | suffices: derived and rebuildable, a bad row is repaired by the reconciler | no |
| `offers_current.product_id`, `price_events.product_id`, `evidence_links.product_id`, `work_queue` ids | catalog write path (`WritePath`) from validated products | suffices (internal ledgers) | no |
| `customer_carts.items[].skuId` | `CartController` refuses ids outside `ProductIds` (`isValid`) | suffices | worth considering later (a `$jsonSchema` on `items[].skuId` with the same pattern) once carts have a stable shape; customer-facing writes are the highest-value place for a backstop |
| `orders.lines[].skuId`, `checkout_quotes.items[].skuId` | built server-side from the quote of validated cart lines; immutable | suffices | candidate with the already-proposed Order/Quote validators in `DATABASE_COLLECTION_CONTRACTS.md` §12 (not added here) |
| `content_blocks` product links | `ContentBlock` validates every product id with `ProductIds` | suffices | no |

**No validator was added in this task.** Recommendations, in order of value: (1) `customer_carts` and `orders`/`checkout_quotes` line SKU patterns together with the already-documented proposed validators, applied with the existing `ValidatorMigration` mechanism (conformance scan first, `moderate`/`warn` before `strict`); (2) nothing for the derived and internal collections. Existing proposals stay in `DATABASE_COLLECTION_CONTRACTS.md` §12.

## 6. Query plans at 100,000+ SKU scale

**Dataset (bulk-inserted into the migrated database, not through the API):**

| Collection | Rows seeded | Data | Indexes |
|---|---|---|---|
| `products` | 100,000 | 37.3 MB | 9.3 MB |
| `product_card_base` | 100,000 | 40.4 MB | 20.3 MB |
| `price_current` | 100,000 |  |  |
| `inventory` | 300,000 | 60.0 MB | 26.0 MB |
| `media_refs` | 100,000 |  |  |
| `offers_current` | 100,000 |  |  |
| `price_events` | 323,000 | 66.4 MB | 30.0 MB |
| `orders` | 20,000 | 5.2 MB | 4.9 MB |
| `customer_carts` | 50,000 |  |  |
| `customers` | 50,000 |  |  |
| `customer_sessions` | 50,000 |  |  |
| `customer_addresses` | 60,000 |  |  |
| `memberships` | 5,000 |  |  |
| `checkout_quotes` | 30,000 |  |  |
| `inventory_reservations` | 20,000 |  |  |
| `customer_address_idempotency` | 20,000 |  |  |
| `import_jobs` | 300 |  |  |
| `import_rows` | 50,000 | 16.6 MB | 9.3 MB |
| `work_queue` | 125,100 | 18.0 MB | 11.5 MB |
| `notification_outbox` | 50,340 | 10.1 MB | 5.6 MB |
| `content_blocks` | 150 |  |  |
| `product_events` | 300,000 | 44.8 MB | 36.9 MB |
| `node_events` | 5,000 |  |  |
| `domain_events` | 20,000 |  |  |
| `support_cases` | 5,000 |  |  |
| `service_areas` | 2,000 |  |  |

Eligible products: about 73,000 of 100,000 (active, confirmed, single or variant_pack); 120 verticals; search tokens follow a skewed distribution (one token at 25%, others at 1-10%, plus a 0.1-10% prevalence sweep); `price_events` is 300,000 paise-shape rows (no `rolled`), 20,000 rolled and 3,000 unrolled legacy offer events; `work_queue` has 100,000 pending card-rebuild rows (the initial backfill of 100,000 SKUs), 100 leased (half lapsed) and 25,000 rows of other types; `notification_outbox` has 5,000 PENDING (4,000 due), 40 SENDING (20 lapsed), 100 FAILED, 45,200 SENT; one heavy customer has 300 orders.

**Method and honesty.** The query shapes are mirrored from the source at the cited `file:line` (filters, sorts, limits, projections, the production predicates `ConsumerEligibility.within/filter` called directly); they were **not** captured from a running service, so a code change after this audit can drift from them. Each `explain("executionStats")` ran twice and the second run is reported. Flagged = in-memory `SORT`, `COLLSCAN`, or (keys or docs examined) / returned > 10.

**Summary: 124 query shapes explained (first-page and keyset variants counted separately). 6 COLLSCANs, all on admin or scheduled paths (the five admin dashboard counts F-3, the reconciler's card count F-4); 19 in-memory SORTs, 13 of them the public-search planner choice (F-2, bounded) and 6 tiny or bounded (F-8, and H01 after `V0019`, which sorts its 3,000 unrolled legacy events); 31 shapes above the 10x examined/returned ratio, all explained in §7; no unbounded scan on a customer-facing read path.** The one finding that needed a code change is F-1, fixed by `V0019` (H01: 323,000 → 3,001 keys examined).

| ID | Query shape | Source | Collection | Plan | Index | nRet | Keys | Docs | ms | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| E01 | product by id (PDP/ConsumerProductResolver) | `ConsumerProductResolver:99` | `products` | IDHACK | `_id` fast path | 1 | 1 | 1 | 0 | ok |
| E02 | consumer list first page, 4-vertical node, page 50 | `ConsumerProductListService:149` | `products` | LIMIT › PROJECTION_DEFAULT › FETCH › SORT_MERGE › IXSCAN×4 | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 51 | 57 | 55 | 0 | ok |
| E03 | consumer list keyset page, 4-vertical node | `ConsumerProductListService:149` | `products` | LIMIT › PROJECTION_DEFAULT › FETCH › SORT_MERGE › IXSCAN×4 | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 51 | 57 | 55 | 0 | ok |
| E04 | consumer list first page, 60-vertical (super-category) scope | `ConsumerProductListService:149` | `products` | LIMIT › PROJECTION_DEFAULT › FETCH › SORT_MERGE › IXSCAN×60 | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 51 | 97 | 53 | 3 | ok |
| E05 | consumer list keyset page, 60-vertical scope | `ConsumerProductListService:149` | `products` | LIMIT › PROJECTION_DEFAULT › FETCH › SORT_MERGE › IXSCAN×60 | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 51 | 96 | 52 | 3 | ok |
| E06 | visibility probe (limit 1) | `ConsumerVisibilityProbe:42` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 1 | 1 | 1 | 0 | ok |
| E07 | commerce list first page (same shape as E02, 1 vertical) | `CommerceListService:165` | `products` | LIMIT › PROJECTION_DEFAULT › FETCH › IXSCAN | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 51 | 51 | 51 | 0 | ok |
| E10 | search tokens [milk] scope=60 verticals first page(20+1) | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 1111 | 1111 | 4 | F-2 [RATIO>10(52.9)] |
| E10b | search tokens [milk] scope=60 verticals keyset page | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 1496 | 1495 | 7 | F-2 [IN-MEMORY-SORT,RATIO>10(71.2)] |
| E11 | search tokens [mi] scope=60 verticals first page(20+1) | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 409 | 409 | 2 | F-2 [RATIO>10(19.5)] |
| E11b | search tokens [mi] scope=60 verticals keyset page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 577 | 577 | 3 | F-2 [RATIO>10(27.5)] |
| E12 | search tokens [fresh] scope=60 verticals first page(20+1) | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 159 | 159 | 0 | ok |
| E12b | search tokens [fresh] scope=60 verticals keyset page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 177 | 177 | 0 | ok |
| E13 | search tokens [zxqrare] scope=60 verticals first page(20+1) | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 5 | 5 | 5 | 0 | F-2 (rare token: sorts its few matches) [IN-MEMORY-SORT] |
| E13b | search tokens [zxqrare] scope=60 verticals keyset page | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 0 | 0 | 0 | 0 | F-2 [IN-MEMORY-SORT] |
| E14 | search tokens [fresh, milk] scope=60 verticals first page(20+1) | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 2987 | 2986 | 27 | F-2 [IN-MEMORY-SORT,RATIO>10(142.2)] |
| E14b | search tokens [fresh, milk] scope=60 verticals keyset page | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 1496 | 1495 | 9 | F-2 [IN-MEMORY-SORT,RATIO>10(71.2)] |
| E15 | search tokens [br1] scope=60 verticals first page(20+1) | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 130 | 130 | 1 | ok |
| E15b | search tokens [br1] scope=60 verticals keyset page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 280 | 280 | 1 | F-2 [RATIO>10(13.3)] |
| S-pvone | search sweep token pvone prevalence 0.1% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 107 | 106 | 1 | F-2 (search sweep) [IN-MEMORY-SORT] |
| S-pvoneb | search sweep token pvone prevalence 0.1% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 42 | 41 | 0 | F-2 (search sweep) [IN-MEMORY-SORT] |
| S-pvfive | search sweep token pvfive prevalence 0.5% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 530 | 529 | 2 | F-2 (search sweep) [IN-MEMORY-SORT,RATIO>10(25.2)] |
| S-pvfiveb | search sweep token pvfive prevalence 0.5% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 267 | 266 | 1 | F-2 (search sweep) [IN-MEMORY-SORT,RATIO>10(12.7)] |
| S-pvten | search sweep token pvten prevalence 1.0% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 952 | 951 | 4 | F-2 (search sweep) [IN-MEMORY-SORT,RATIO>10(45.3)] |
| S-pvtenb | search sweep token pvten prevalence 1.0% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 475 | 474 | 2 | F-2 (search sweep) [IN-MEMORY-SORT,RATIO>10(22.6)] |
| S-pvfifteen | search sweep token pvfifteen prevalence 1.5% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 1241 | 1241 | 7 | F-2 (search sweep) [RATIO>10(59.1)] |
| S-pvfifteenb | search sweep token pvfifteen prevalence 1.5% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 769 | 768 | 5 | F-2 (search sweep) [IN-MEMORY-SORT,RATIO>10(36.6)] |
| S-pvtwenty | search sweep token pvtwenty prevalence 2.0% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 1063 | 1063 | 4 | F-2 (search sweep) [RATIO>10(50.6)] |
| S-pvtwentyb | search sweep token pvtwenty prevalence 2.0% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | PROJECTION_SIMPLE › SORT › FETCH › IXSCAN | `card_search_tokens` | 21 | 1011 | 1010 | 4 | F-2 (search sweep) [IN-MEMORY-SORT,RATIO>10(48.1)] |
| S-pvfifty | search sweep token pvfifty prevalence 5.0% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 436 | 436 | 1 | F-2 (search sweep) [RATIO>10(20.8)] |
| S-pvfiftyb | search sweep token pvfifty prevalence 5.0% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 347 | 347 | 1 | F-2 (search sweep) [RATIO>10(16.5)] |
| S-pvhund | search sweep token pvhund prevalence 10.0% scope=ALL(120) first page | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 307 | 307 | 1 | F-2 (search sweep) [RATIO>10(14.6)] |
| S-pvhundb | search sweep token pvhund prevalence 10.0% scope=ALL keyset | `CommerceSearchService:138` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 21 | 304 | 304 | 1 | F-2 (search sweep) [RATIO>10(14.5)] |
| E16 | search step 2: eligibility re-check by _id $in 21 | `CommerceSearchService:148` | `products` | PROJECTION_DEFAULT › FETCH › IXSCAN | `_id_` | 5 | 42 | 21 | 0 | ok |
| E20 | product_card_base by sku batch $in 50 | `ProductCardBaseReader:41` | `product_card_base` | FETCH › IXSCAN | `sku_id_1` | 50 | 100 | 50 | 0 | ok |
| E21 | products by _id $in 50 (batch) | `CommerceProductBatchService:127` | `products` | FETCH › IXSCAN | `_id_` | 50 | 100 | 50 | 1 | ok |
| E22 | price_current batch $in 50 + currency | `PricingService:239` | `price_current` | FETCH › IXSCAN | `sku_id_1_currency_1` | 50 | 100 | 50 | 0 | ok |
| E23 | inventory batch $in 50 + location | `InventoryService:390` | `inventory` | FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 50 | 149 | 50 | 0 | ok |
| E24 | price_current point (sku,currency) | `PricingService:191` | `price_current` | FETCH › IXSCAN | `sku_id_1_currency_1` | 1 | 1 | 1 | 0 | ok |
| E25 | inventory point (sku,location) | `InventoryService:521` | `inventory` | FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 1 | 1 | 1 | 0 | ok |
| E26 | media_refs by owner (PRODUCT) | `MediaService:208` | `media_refs` | FETCH › IXSCAN | `owner_type_1_owner_id_1` | 1 | 1 | 1 | 0 | ok |
| E27 | offers_current by product_id (merge/offer upsert prefix) | `OffersService/MergeService` | `offers_current` | FETCH › IXSCAN | `product_id_1_source_1_seller_1_channel_1` | 1 | 1 | 1 | 0 | ok |
| E30 | admin inventory list first page (limit 51) | `InventoryService:449` | `inventory` | LIMIT › FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 51 | 51 | 51 | 0 | ok |
| E31 | admin inventory list keyset page | `InventoryService:449` | `inventory` | LIMIT › FETCH › SORT_MERGE › IXSCAN×2 | `sku_id_1_fulfillment_location_id_1` | 201 | 201 | 201 | 0 | ok |
| E32 | admin inventory list location filter | `InventoryService:449` | `inventory` | LIMIT › FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 201 | 603 | 603 | 1 | ok |
| E33 | admin inventory list state=OUT_OF_STOCK (limit 201) | `InventoryService:449` | `inventory` | LIMIT › FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 201 | 3768 | 3768 | 8 | F-7 [RATIO>10(18.7)] |
| E34 | admin inventory list state=LOW_STOCK (limit 201) | `InventoryService:449` | `inventory` | LIMIT › FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 201 | 2436 | 2436 | 4 | F-7 [RATIO>10(12.1)] |
| E35 | admin inventory list state=IN_STOCK (limit 201) | `InventoryService:449` | `inventory` | LIMIT › FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 201 | 249 | 249 | 0 | ok |
| E36 | admin inventory list state=INACTIVE (limit 201) | `InventoryService:449` | `inventory` | LIMIT › FETCH › IXSCAN | `sku_id_1_fulfillment_location_id_1` | 201 | 3910 | 3910 | 3 | F-7 [RATIO>10(19.5)] |
| E40 | admin product list, no filter (limit 51) | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `_id_` | 51 | 51 | 51 | 0 | ok |
| E41 | admin product list vertical filter | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `product_vertical_id_cursor` | 51 | 51 | 51 | 0 | ok |
| E42 | admin product list vertical+lifecycle+status | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 51 | 51 | 51 | 0 | ok |
| E43 | admin product list lifecycle=archived only (5%) | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `_id_` | 51 | 1020 | 1020 | 0 | F-6 [RATIO>10(20.0)] |
| E44 | admin product list status=provisional only (10%) | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `_id_` | 51 | 504 | 504 | 0 | ok |
| E45 | admin product list lifecycle=active + keyset | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `_id_` | 51 | 60 | 60 | 0 | ok |
| E46 | admin product list vertical + keyset | `ProductQueryService:67` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `product_vertical_id_cursor` | 51 | 51 | 51 | 0 | ok |
| E50 | import_rows page (job,row>=5000) limit 500 | `ImportJobRepository:372` | `import_rows` | LIMIT › FETCH › IXSCAN | `import_rows_by_job_row` | 500 | 500 | 500 | 0 | ok |
| E51 | import_rows page bounded [from,below) | `ImportJobRepository:378` | `import_rows` | LIMIT › FETCH › IXSCAN | `import_rows_by_job_row` | 500 | 500 | 500 | 0 | ok |
| E52 | import_rows byOutcome | `ImportJobRepository:384` | `import_rows` | LIMIT › FETCH › IXSCAN | `import_rows_by_job_row` | 100 | 1753 | 1753 | 1 | F-8 (one job, bounded by its rows) [RATIO>10(17.5)] |
| E53 | import_rows negatives (one pass) | `ImportJobRepository:395` | `import_rows` | LIMIT › FETCH › IXSCAN | `import_rows_by_job_row` | 100 | 1183 | 1183 | 1 | F-8 [RATIO>10(11.8)] |
| E54 | import_rows by _id (apply row update) | `ImportJobRepository:214` | `import_rows` | IDHACK | `_id` fast path | 1 | 1 | 1 | 0 | ok |
| E55 | import_jobs list (all), newest first | `ImportJobRepository:85` | `import_jobs` | LIMIT › FETCH › IXSCAN | `_id_` | 51 | 51 | 51 | 0 | ok |
| E56 | import_jobs list by status | `ImportJobRepository:85` | `import_jobs` | LIMIT › FETCH › IXSCAN | `import_jobs_by_status_recent` | 51 | 51 | 51 | 0 | ok |
| E57 | import_jobs worker claim (lease lapsed) | `ImportJobRepository:154` | `import_jobs` | UPDATE › FETCH › SORT › IXSCAN | `import_jobs_claim` | 1 | 9 | 1 | 0 | F-8 (sorts the ≤13 candidate jobs) [IN-MEMORY-SORT] |
| E58 | import gauge: oldest VALIDATING/APPLYING by updated_at | `ImportJobGauges:77` | `import_jobs` | PROJECTION_SIMPLE › FETCH › SORT › IXSCAN | `import_jobs_claim` | 1 | 13 | 1 | 0 | F-8 [IN-MEMORY-SORT,RATIO>10(13.0)] |
| E59 | import gauge: count by status (cap 10000) | `ImportJobGauges:75` | `import_jobs` | LIMIT › IXSCAN | `import_jobs_by_status_recent` | 255 | 255 | 0 | 0 | ok |
| E60 | work_queue rebuild claim (pending or lapsed lease) | `ProjectionRebuildWorker:142` | `work_queue` | UPDATE › FETCH › OR › IXSCAN › FETCH › IXSCAN | `status_1_type_1` | 1 | 101 | 101 | 1 | F-9 [RATIO>10(101.0)] |
| E61 | work_queue V0018 lookup: oldest pending by requested_at | `ProjectionQueueGauges:99` | `work_queue` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `projection_rebuild_pending_by_requested` | 1 | 1 | 1 | 0 | ok |
| E62 | work_queue V0018 lookup: oldest lapsed lease | `ProjectionQueueGauges:99` | `work_queue` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `projection_rebuild_leased_by_lease` | 1 | 1 | 1 | 0 | ok |
| E63 | work_queue gauge count pending (cap 10000) | `ProjectionQueueGauges:92` | `work_queue` | LIMIT › IXSCAN | `status_1_type_1` | 10000 | 10000 | 0 | 4 | ok |
| E64 | work_queue gauge count lapsed | `ProjectionQueueGauges:92` | `work_queue` | LIMIT › IXSCAN | `projection_rebuild_leased_by_lease` | 50 | 50 | 0 | 0 | ok |
| E65 | work_queue gauge count live leases | `ProjectionQueueGauges:91` | `work_queue` | LIMIT › IXSCAN | `projection_rebuild_leased_by_lease` | 50 | 50 | 0 | 0 | ok |
| E66 | work_queue point by _id (enqueue upsert) | `ProjectionRebuildQueue:71` | `work_queue` | IDHACK | `_id` fast path | 1 | 1 | 1 | 0 | ok |
| E70 | notification due scan / claimNext | `NotificationOutbox:107` | `notification_outbox` | UPDATE › SUBPLAN › LIMIT › FETCH › SORT_MERGE › IXSCAN › FETCH › IXSCAN | `notification_due` | 1 | 2 | 2 | 0 | ok |
| E71 | notification due scan as find (limit 1) | `NotificationOutbox:107` | `notification_outbox` | SUBPLAN › LIMIT › FETCH › SORT_MERGE › IXSCAN › FETCH › IXSCAN | `notification_due` | 1 | 2 | 2 | 0 | ok |
| E72 | dashboard/gauge count notification by status | `DashboardSummaryService:97` | `notification_outbox` | LIMIT › IXSCAN | `notification_due` | 10000 | 10000 | 0 | 4 | ok |
| E73 | notification by customer (erasure) | `NotificationErasure` | `notification_outbox` | FETCH › IXSCAN | `notification_by_customer` | 4 | 4 | 4 | 0 | ok |
| E80 | order list by customer (typical) | `OrderRepository:93` | `orders` | LIMIT › FETCH › IXSCAN | `order_by_customer_recent` | 1 | 1 | 1 | 0 | ok |
| E81 | order list by customer (heavy: 300 orders) first page | `OrderRepository:93` | `orders` | LIMIT › FETCH › IXSCAN | `order_by_customer_recent` | 21 | 22 | 22 | 0 | ok |
| E82 | order list by customer (heavy) keyset | `OrderRepository:93` | `orders` | LIMIT › FETCH › SORT_MERGE › IXSCAN×2 | `order_by_customer_recent`, `order_recent` | 21 | 23 | 23 | 1 | ok |
| E83 | staff order queue (all visible statuses) first page | `OrderRepository:132` | `orders` | LIMIT › FETCH › SORT_MERGE › IXSCAN×4 | `order_by_status_recent` | 51 | 54 | 51 | 0 | ok |
| E84 | staff order queue keyset | `OrderRepository:132` | `orders` | LIMIT › FETCH › SORT_MERGE › IXSCAN×2 | `order_recent` | 51 | 51 | 51 | 1 | ok |
| E85 | staff order queue status=CONFIRMED | `OrderRepository:132` | `orders` | LIMIT › FETCH › IXSCAN | `order_by_status_recent` | 51 | 51 | 51 | 0 | ok |
| E86 | order by (customerId,quoteId) idempotency | `OrderRepository:41` | `orders` | FETCH › IXSCAN | `order_one_per_quote` | 0 | 0 | 0 | 0 | ok |
| E87 | order by _id + customer (detail) | `OrderRepository:54` | `orders` | FETCH › IXSCAN | `_id_` | 0 | 1 | 1 | 0 | ok |
| E90 | cart by customer (_id) | `CartRepository:38` | `customer_carts` | IDHACK | `_id` fast path | 1 | 1 | 1 | 0 | ok |
| E91 | customer by phone (login) | `CustomerRepository:51` | `customers` | FETCH › IXSCAN | `customer_one_per_phone` | 1 | 1 | 1 | 0 | ok |
| E92 | session by _id | `CustomerSessionRepository:73` | `customer_sessions` | IDHACK | `_id` fast path | 1 | 1 | 1 | 0 | ok |
| E93 | addresses by customer newest-first | `AddressRepository:59` | `customer_addresses` | FETCH › IXSCAN | `address_by_customer_updated` | 2 | 2 | 2 | 0 | ok |
| E94 | membership current candidates (customer,status) | `MembershipRepository:96` | `memberships` | LIMIT › FETCH › IXSCAN | `membership_active_by_customer` | 1 | 1 | 1 | 0 | ok |
| E95 | checkout quote idempotency (customer,digest) | `CheckoutQuoteRepository` | `checkout_quotes` | FETCH › IXSCAN | `checkout_quote_one_per_idempotency_key` | 1 | 1 | 1 | 0 | ok |
| E96 | address idempotency by _id | `AddressIdempotencyRepository:50` | `customer_address_idempotency` | IDHACK | `_id` fast path | 1 | 1 | 1 | 0 | ok |
| E97 | address idempotency by customer (erasure) | `AddressErasure` | `customer_address_idempotency` | FETCH › IXSCAN | `address_idempotency_by_customer` | 1 | 1 | 1 | 0 | ok |
| E98 | inventory reservation by orderId | `InventoryReservationRepository:43` | `inventory_reservations` | FETCH › IXSCAN | `inventory_reservation_one_per_order` | 1 | 1 | 1 | 0 | ok |
| E99 | inventory reservation expiry batch (limit 100) | `InventoryReservationRepository:57` | `inventory_reservations` | LIMIT › FETCH › IXSCAN | `inventory_reservation_expiry` | 100 | 100 | 100 | 0 | ok |
| F01 | content admin list HOME | `ContentService:163` | `content_blocks` | FETCH › SORT › IXSCAN | `content_by_placement_status_sort` | 120 | 120 | 120 | 0 | F-8 (≤200 docs, capped) [IN-MEMORY-SORT] |
| F02 | content live HOME (home banners) | `ContentService:179` | `content_blocks` | LIMIT › FETCH › IXSCAN | `content_by_placement_status_sort` | 60 | 60 | 60 | 0 | ok |
| F03 | content preview HOME (PUBLISHED+DRAFT) | `ContentService:293` | `content_blocks` | LIMIT › FETCH › SORT_MERGE › IXSCAN×2 | `content_by_placement_status_sort` | 90 | 90 | 90 | 0 | ok |
| F04 | content count per placement (create cap) | `ContentService:89` | `content_blocks` | IXSCAN | `content_by_placement_status_sort` | 90 | 91 | 0 | 0 | ok |
| G01 | audit product_events recent first page (101) | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › IXSCAN | `audit_read_recent` | 101 | 101 | 101 | 0 | ok |
| G02 | audit product_events by actor | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › IXSCAN | `audit_read_actor` | 101 | 101 | 101 | 0 | ok |
| G03 | audit product_events by request_id | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › IXSCAN | `audit_read_request` | 1 | 1 | 1 | 0 | ok |
| G04 | audit product_events by target product_id | `AuditEventReader:57` | `product_events` | SORT › FETCH › IXSCAN | `product_id_1_at_1` | 3 | 3 | 3 | 0 | F-8 (one product's events) [IN-MEMORY-SORT] |
| G05 | audit product_events by action (type) | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › IXSCAN | `audit_read_recent` | 101 | 954 | 954 | 2 | ok |
| G06 | audit product_events time window (7d) | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › IXSCAN | `audit_read_recent` | 101 | 101 | 101 | 0 | ok |
| G07 | audit product_events keyset (at<) | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › SORT_MERGE › IXSCAN×2 | `audit_read_recent` | 101 | 101 | 101 | 0 | ok |
| G08 | audit product_events actor+action | `AuditEventReader:57` | `product_events` | LIMIT › FETCH › IXSCAN | `audit_read_actor` | 101 | 1013 | 1013 | 2 | F-5 [RATIO>10(10.0)] |
| G09 | audit node_events recent | `AuditEventReader:57` | `node_events` | LIMIT › FETCH › IXSCAN | `audit_read_recent` | 101 | 101 | 101 | 0 | ok |
| G10 | audit domain_events by target aggregate_id | `AuditEventReader:57` | `domain_events` | LIMIT › FETCH › IXSCAN | `audit_read_recent` | 40 | 20000 | 20000 | 32 | F-5 [RATIO>10(500.0)] |
| G12 | audit domain_events by target type+id | `AuditEventReader:57` | `domain_events` | SORT › FETCH › IXSCAN | `aggregate_type_1_aggregate_id_1_at_1` | 40 | 40 | 40 | 0 | F-8 [IN-MEMORY-SORT] |
| G11 | audit domain_events recent | `AuditEventReader:57` | `domain_events` | LIMIT › FETCH › IXSCAN | `audit_read_recent` | 101 | 101 | 101 | 0 | ok |
| H01 | rollup scan (legacy offer events, unbounded list) | `RollupService:73` | `price_events` | FETCH › SORT › IXSCAN | `price_events_legacy_unrolled` | 3000 | 3001 | 3000 | 18 | F-1 (fixed by V0019: was 323,000 keys/docs, 694-757 ms) [IN-MEMORY-SORT] |
| H02 | reconciler: count eligible products (no cap) | `ProjectionReconciler:102` | `products` | FETCH › IXSCAN | `classification.vertical_id_1_lifecycle_1_classification.status_1__id_1` | 73000 | 75030 | 75000 | 103 | ok |
| H03 | reconciler: count projection rows (no cap) | `ProjectionReconciler:113` | `product_card_base` | COLLSCAN | `_id` fast path | 100000 | 0 | 100000 | 27 | F-4 [COLLSCAN] |
| H04 | reconciler: eligible products page by _id (limit 500) | `ProjectionReconciler:173` | `products` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `_id_` | 500 | 684 | 684 | 1 | ok |
| H05 | reconciler: card page by sku_id (limit 500) | `ProjectionReconciler:184` | `product_card_base` | LIMIT › PROJECTION_SIMPLE › FETCH › IXSCAN | `sku_id_1` | 500 | 500 | 500 | 0 | ok |
| H10 | dashboard orders open CONFIRMED (cap 10000) | `DashboardSummaryService:64` | `orders` | LIMIT › IXSCAN | `order_by_status_recent` | 2950 | 2950 | 0 | 1 | ok |
| H11 | dashboard orders status+24h | `DashboardSummaryService:68` | `orders` | LIMIT › IXSCAN | `order_by_status_recent` | 8 | 8 | 0 | 0 | ok |
| H12 | dashboard inventory out_of_stock | `DashboardSummaryService:73` | `inventory` | LIMIT › COLLSCAN | `_id` fast path | 10000 | 0 | 203045 | 76 | F-3 [COLLSCAN,RATIO>10(20.3)] |
| H13 | dashboard inventory low_stock | `DashboardSummaryService:74` | `inventory` | LIMIT › COLLSCAN | `_id` fast path | 5410 | 0 | 300000 | 229 | F-3 [COLLSCAN,RATIO>10(55.5)] |
| H14 | dashboard products lifecycle=active | `DashboardSummaryService:80` | `products` | LIMIT › COLLSCAN | `_id` fast path | 10000 | 0 | 11764 | 4 | F-3 [COLLSCAN] |
| H15 | dashboard products lifecycle=draft | `DashboardSummaryService:81` | `products` | LIMIT › COLLSCAN | `_id` fast path | 10000 | 0 | 99999 | 33 | F-3 [COLLSCAN] |
| H16 | dashboard service_areas active | `DashboardSummaryService:86` | `service_areas` | LIMIT › COLLSCAN | `_id` fast path | 2000 | 0 | 2000 | 1 | F-3 (2,000 rows) [COLLSCAN] |
| H17 | dashboard support by status | `DashboardSummaryService:91` | `support_cases` | LIMIT › IXSCAN | `support_by_status_recent` | 1000 | 1000 | 0 | 1 | ok |

## 7. Findings

Severity: **High** = wrong or unavailable at the target scale; **Medium** = real defect or a capacity limit within an order of magnitude of the target; **Low** = bounded, documented or admin-only; **Info** = recorded, no action.

| ID | Severity | Finding | Evidence | Disposition |
|---|---|---|---|---|
| **F-1** | **Medium** (real defect, latent; **fixed**) | The hourly price rollup reads the whole append-only `price_events` ledger. Paise rows have no `rolled` flag, so they sit inside the `rolled != true` range; the planner used `product_id_1_ts_1` and sorted the legacy matches in memory. Cost grows with the ledger, not with the work, inside one transaction with an unbounded in-memory list | H01 before: 323,000 keys + 323,000 docs for 3,000 returned, 694-757 ms. After `V0019`: 3,001 keys, 3,000 docs, 18-24 ms. Extrapolation: about 28 million ledger rows would exceed MongoDB's default 60 s transaction lifetime (2.2 µs/row measured); a catalogue of 100,000 SKUs repriced daily writes 36.5 million rows a year | **Fixed in this branch**: migration `V0019__price_events_legacy_unrolled_index` (partial index, filter = the query's own three type predicates; zero write cost for paise rows). `PriceEventsRollupIndexIT` proves the plan and the row-set equality and fails without the index. Existing indexes kept |
| F-2 | Low (capacity) | Public search: the planner picks between walking `sku_id` order (examines about 21/p rows) and fetching then sorting every match (N·p rows). Both are bounded, but several shapes exceed 10x examined/returned, and multi-token queries and mid-selectivity tokens sort in memory. No `maxTime` on the search query | worst measured on 100,000 cards: 2,987 docs and a 3,000-row in-memory sort for `fresh milk` (27 ms, E14); 1,111-1,496 docs for one 3% token (4-7 ms); the sweep peaks at 1,241 docs (7 ms) near 1.5% prevalence. Model (not measured above 100,000): the cheaper plan peaks near p ≈ √(21/N) at ≈ √(21·N) docs: about 1,450 at 100,000, 4,600 at 1,000,000, 14,500 at 10,000,000 | No index fixes it (a prefix range on a multikey token cannot deliver `sku_id` order). Recommendation: add `maxTime` (for example 2 s, like the inventory list) so a skewed catalogue cannot hold a connection; keep the 5-token cap. No change made |
| F-3 | Low-Medium (capacity, admin only) | Admin dashboard counts scan: `products` by `lifecycle` (no index with `lifecycle` as prefix) and `inventory` by `active`/`on_hand` (and `$expr` for low stock). Bounded by `cap` 10,000 and `maxTime` 2 s, which turns an overrun into 503 "dashboard unavailable" | H13: all 300,000 stock rows, 229 ms; H12: 203,045 docs, 76 ms; H15: 99,999 products, 33 ms; H14: 11,764 products, 4 ms. The 2 s limit is reached at about 2.6 million stock rows (about 870,000 SKUs at three locations), roughly 9x this dataset (extrapolation) | Not changed (admin-only, guarded, and an index on the busiest write collection is the owner's trade-off). Options: cache the summary (`SnapshotCache` exists), or a partial index `inventory {active:1, on_hand:1}` for out-of-stock only, or `products {lifecycle:1}`; low-stock needs a stored derived field to be index-served |
| F-4 | Low (scheduled) | `ProjectionReconciler` counts with unbounded `countDocuments`: the card count is a COLLSCAN (27-28 ms at 100,000), the eligible-products count fetches every eligible document (103-136 ms for 73,000-75,000) | H02, H03. Linear: about 1-2 s per pass at 1,000,000 | `estimatedDocumentCount()` for the card count would remove the scan; background job, no change made |
| F-5 | Low (documented) | Audit read by `targetId` without `targetType` on `domain_events` walks `audit_read_recent` (the leading `aggregate_type` of the legacy index is missing); `action`/`actor+action` filters are low-selectivity | G10: 20,000 keys and docs for 40 returned (32 ms); G05/G08: about 1,000 keys for 101. Already recorded as a SCAN RISK in `DATABASE_INDEX_MANIFEST.md` §10. With `targetType` + `targetId` the legacy index is used (G12) | Bounded by the 5 s `maxTime` and the 100-row page; `domain_events` is low volume. No change |
| F-6 | Info | Admin product list filtered only by a sparse `lifecycle` or `status` walks `_id` order with a residual filter | E43: 1,020 docs for 51 returned (archived = 5%); E44 504 (provisional = 10%). With the vertical, the vertical+`_id` and PAG-2 indexes serve it (E41, E42, E46) | Admin, paged. No change |
| F-7 | Info | Admin inventory list with a state filter examines 12-20x the page | E33/E34/E36: 2,436-3,910 docs for 201 rows (3-8 ms); bounded by `LIST_MAX_TIME_MS` 2 s then `InventoryListTimeoutException`; documented in the code | No change |
| F-8 | Info | Bounded in-memory sorts: import-job claim (sorts at most the lapsed-lease jobs, 9-13 keys), content admin list (≤ 200 blocks per placement), audit by product (one product's events), import error reads (rows of one job) | E52, E53, E57, E58, F01, G04, G12 | No change |
| F-9 | Info | Rebuild-queue claim examines the leased set (101 keys for 1 returned) because the `$or` second branch walks `status = leased` | E60. Bounded by the number of leased rows (about the worker count) and independent of the 100,000 pending rows | No change. The two `V0018` lookups are single-key index walks (E61, E62) |
| F-10 | Info | `V0017` and `V0004` are recorded `adopted` (no-ops) on a fresh database | §3.1 | Documented here and in the runbook |
| F-11 | Low (fixed) | Documentation drift: collection counts 49/50/51/55, "exactly four TTL indexes", the runbook's "51 indexes", the retention header, missing `import_*` rows in the contracts, the inventory header | §11 | Fixed |
| F-12 | Info | (withdrawn) an earlier draft of this audit read a stale `CLAUDE.md` that said Spring Boot 3.3.5; `CLAUDE.md` on `main` already says 4.1.x (updated in #113), matching the `pom.xml` | the parent in `pom.xml`, `CLAUDE.md` | none needed |
| F-13 | Info | Test pin: `DatastorePrivilegeIT` hard-codes the number of applied migrations (was 18) | failed on the first full run after `V0019` | Updated to 19 in the `V0019` commit |

No **High** finding. No checksum mismatch. No missing index for a request path. No COLLSCAN on a customer or catalogue request path.

## 8. Architecture statement for 100,000+ SKUs

**No core redesign is needed.** The consumer and commerce read paths are bounded by construction and by the measured plans:

* Pages are capped: consumer list and search 50 (`MAX_PAGE_SIZE`), product batch 50 ids, inventory admin list 200 (`LIST_MAX_LIMIT`), audit page 100, content 200 blocks per placement, order pages 50, reservation 50 distinct items, synchronous import 500 rows. Every list is keyset-paged (`_id`, `sku_id`, `(createdAt, _id)`); there is no `skip`.
* Product by id, batches of 50 (`products`, `product_card_base`, `price_current`, `inventory`), consumer list (4 and 60 vertical scopes: 52-55 docs for 51 rows via a `SORT_MERGE` of per-vertical `IXSCAN`s on the PAG-2 index, 0-3 ms), media, orders, carts, sessions, idempotency lookups, reservations, the audit default/actor/request pages, content, the notification due scan (2 keys), the import row pages, and the two `V0018` lookups are all `IXSCAN`/`IDHACK` with examined ≈ returned.
* Counts that feed gauges and the dashboard carry `limit(cap)` and `maxTime` (2 s).
* Storage at this scale is small: `products` 37 MB + 9 MB of indexes, `product_card_base` 40 MB + 20 MB, `inventory` (300,000 rows) 60 MB + 26 MB, `price_events` (323,000 rows) 66 MB + 30 MB. The working set fits in memory of any ordinary instance.

**Capacity risks, with numbers:** (1) the price ledger scan was the one unbounded read (F-1, fixed); (2) the dashboard counts reach their 2 s guard near 2.6 million stock rows (F-3); (3) public search examines up to about √(21·N) rows per page (about 1,450 at 100,000; F-2); (4) the reconciler's counts are linear in the catalogue (F-4); (5) `price_events` and the audit ledgers grow without bound by design (R1, no TTL): their indexes are the only access path, size them (about 30 MB of index for 323,000 events here); (6) `inventory_reservations`, `checkout_quotes`, `orders`, `customer_carts`, `import_rows` and the non-rebuild `work_queue` rows are never purged: a retention decision (`TBD — PRODUCTION POLICY`) is needed before they reach the tens of millions.

## 9. Data-safety review of the migrations

* **DATA-kind migrations: exactly one, `V0004`** (`attribute_schemas`: set `required=false` for `pack_size`/`pack_unit` on the seed schema ids at `version: 1` only). It needs per-id approval (`approved-data-migrations`); without it the runner reports `PENDING_APPROVAL` and does nothing; it is forward-only (no automatic restore; restore from backup); its preflight counts the documents it would change; its `validate` re-counts. On a database seeded by `V0003` it is a no-op (`ALREADY_SATISFIED` → `ADOPTED`). `DatastorePrivilegeIT` applies it with approval as the migrator identity.
* **`V0003` (REFERENCE_INIT)** is insert-if-absent: it never replaces an existing document. **`V0017`** patches the live validator only after a conformance scan (refuses with count and up to 5 sample ids, never rewrites documents) and records the previous options as `rollbackInfo`. All other migrations create indexes (or, `V0101`/`V0102`, drop one, disabled by default): none deletes or rewrites business data.
* **Rollback information:** recorded by validator and drop migrations (`rollbackInfo`); index creations roll forward by a new `DropIndexMigration` (the previous state is "no index"); the history records are never deleted. On a fresh database no history row carries `rollbackInfo` (it is recorded only when an apply changes something: none of the 19 does there, and an adopted migration records none).
* **Dry run:** read-only; verified: on an empty database it created no collection (including no history or lock collection), and its steps are `WOULD_APPLY`/`WOULD_ADOPT`; on a migrated database every step is `ALREADY_APPLIED`.
* **Unique-index migrations** (`V0005`, `V0006`, and the baseline when an index is missing) run a read-only duplicate check first and `BLOCK` with a sample instead of deleting or merging. `V0016`/`V0019` create no unique index over existing data (`V0016`'s collections are new).
* **Index build cost on a large existing collection** (`V0019` on a large `price_events`, `V0009` on `product_card_base`) is not measured here (UNVERIFIED): MongoDB 7 builds online, but run them in a quiet window.

## 10. Recommendations

1. Merge `V0019` (F-1). Run it as every migration: `DRY_RUN`, review, `APPLY`.
2. Add `maxTime` to the public search query (F-2); optional.
3. Decide the dashboard approach before about 500,000 SKUs: a cached summary or a partial index (F-3).
4. Use `estimatedDocumentCount()` for the reconciler's card count (F-4).
5. Add line-SKU patterns for carts/orders/quotes with the proposed validators when those are implemented (§5.1).
6. Update `CLAUDE.md` (Spring Boot 4.1.1) (F-12).
7. Schedule DB-7 follow-ups that this audit did not cover: a concurrent load test, a production-size index build rehearsal, the same explain matrix on Atlas.
8. Take the owner's decision on the two disabled drop candidates (`V0101`, `V0102`); they remain unused indexes.

## 11. Documentation corrected by this audit

`DATABASE_INDEX_MANIFEST.md` (83 indexes, V0019 section, seven TTL indexes on six collections, `IndexContractIT` description, §9 note on partial indexes); `DATABASE_MIGRATION_RUNBOOK.md` (V0019 row, adoption list, resulting index set, recalculated counts); `DATABASE_INVENTORY.md` (Phase 10 banner, 57 collections, 56 collections without a validator, seven TTL); `DATABASE_COLLECTION_CONTRACTS.md` (57 collections, `import_jobs`/`import_rows` rows, tallies); `DATABASE_RETENTION_AND_PII.md` (57); `DATABASE_STAGING_RUNBOOK.md` (57 application collections, 59 physical); `ENGINEERING_STATUS.md` (Phase 10 entry, DB-4 line, DB-7 status). Historical dated entries that state the numbers of their day were left as they are.

## 12. NOT VERIFIED

* Production/Atlas: index set, data volume, plan stability, index build time, replica-set behaviour, TLS/auth; all runs were single-node, unauthenticated, local, synthetic.
* Concurrency and tail latency under load (explain is single-threaded and warm); TTL deletion timing; cold-cache I/O.
* The explained queries are mirrored from source at the cited lines, not captured from a running service (no profiler cross-check); distributions are synthetic (verticals, token skew, order spread).
* Planner choices on other MongoDB versions (7.0.43 only), and the slot-based engine.
* The extrapolations in §7/§8 (28 million rows, 2.6 million stock rows, √(21·N)) are models from one dataset size.
* Index build time of `V0019` and `V0009` on production-size collections.

## 13. Reproduction

The harnesses are deliberately not committed. They are three JUnit classes in package `com.tazzzo.catalog.migration` that extend `AbstractMigrationIT` (scratch database, real runner): (1) apply `Migrations.defaults(...)` incrementally, read `schema_migrations`, recompute every checksum from `Checksums.sha256`, compare with `MigrationRegistryTest.RELEASED`, run the tamper cases, dump `listIndexes()` and `listCollections()` options; (2) bulk-insert the dataset of §6 with `insertMany` (5,000-document unordered batches) and run `find(...).explain(ExplainVerbosity.EXECUTION_STATS)` (or `runCommand({explain: {findAndModify|aggregate ...}})` for claims and counts) for each shape; (3) the rollup before/after probe. Run with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -o test -Dtest=<Class>`; Testcontainers starts and removes the `mongo:7` containers. Repository tests that guard the results: `MigrationRegistryTest`, `IndexContractIT`, `DatabaseDocsConsistencyTest`, `DatastorePrivilegeIT`, `PriceEventsRollupIndexIT`, `WorkQueueRebuildIndexIT`.
