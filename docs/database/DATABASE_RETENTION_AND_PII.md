# DATABASE_RETENTION_AND_PII — tazzzo-backend (DB-4)

The retention matrix and the personal-data map for the 50 application collections of `SchemaBootstrap.COLLECTIONS`
(plus the two migration bookkeeping collections, §4). **It records what the code and the database do today and marks every
unresolved policy `TBD — PRODUCTION POLICY`. No retention period, legal basis or deletion promise is invented here.** Owner, class and the
"none" retention values are taken from `DATABASE_COLLECTION_CONTRACTS.md` §5 (single source); TTL indexes from
`DATABASE_INDEX_MANIFEST.md` and `IndexContractIT`; personal-data fields from the collection contracts and the repositories.

`DatabaseDocsConsistencyTest` fails if this matrix does not list exactly the collections in `SchemaBootstrap.COLLECTIONS`.

## 1. Definitions

| Term | Meaning |
|---|---|
| durable / temporary | temporary = removed by a TTL index; durable = kept until something deletes it deliberately (today almost nothing does) |
| TTL | a MongoDB TTL index; **only four exist** (OTP challenges ×2, OTP grants, sessions) and `IndexContractIT` fails if any other collection gains one |
| DIRECT PII | identifies or contacts a person: phone, name, e-mail, postal address, coordinates |
| credential-derived | a keyed digest of a secret (never the secret): treated as sensitive |
| customer link | the opaque `customerId`: pseudonymous, but links records to the PII in `customers`/`customer_profiles`/`customer_addresses` |
| staff identifier | the `actor` sub-document on audit events: `google:<subject>`, the credential label and the server-generated request id; no e-mail, name, IP or user agent is stored (`Actor` forbids them) |
| `TBD — PRODUCTION POLICY` | a production policy that the owner, legal and infrastructure must decide; nothing here pre-empts it |

## 2. Retention matrix (49 collections)

| Collection | Owner | Class | Durable / temporary | TTL | Business retention | Personal / sensitive data | Privacy deletion handling | Legal hold | Backup behaviour |
|---|---|---|---|---|---|---|---|---|---|
| `products` | catalog | authoritative | durable | none | retain (authoritative catalogue data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `gtin_registry` | catalog | authoritative | durable | none | retain (authoritative catalogue data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `identity_keys` | catalog | authoritative | durable | none | retain (authoritative catalogue data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `canonical_keys` | catalog | authoritative | durable | none | retain (authoritative catalogue data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `discriminating_attributes` | catalog (reference) | authoritative | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `brands` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `product_events` | catalog | event | durable (audit ledger) | none (asserted by `IndexContractIT`) | UNDECIDED / DEPLOYMENT POLICY; no TTL and no deletion; tiering is a DB-6 decision | **staff identifier**: `actor.id` (`google:<subject>` / `service:<role>` / `system:<name>`), `actor.credential_id`, `actor.request_id`; free-form `detail` is not schema-constrained | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `classification_history` | catalog | event | durable (event, no reader) | none | UNDECIDED / DEPLOYMENT POLICY; no deletion | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `evidence` | catalog | authoritative | durable | none | retain (authoritative catalogue data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `evidence_links` | catalog | derived/relationship | durable | none | retain (authoritative catalogue data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `work_queue` | catalog/commerce | operational | operational | none | none; only `card_rebuild` rows are removed by the worker; completed rows accumulate (DB-0 R13) | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `offers_current` | catalog | authoritative (raw input) | durable | none | retain; a product merge repoints or deletes the loser's offers (`MergeService`) | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `catalogue_releases` | catalog (taxonomy) | authoritative | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `batches` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `campaigns` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `campaign_membership` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `aliases` | catalog (taxonomy) | authoritative (reference) | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `variant_groups` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `marketplace_crosswalks` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `system_config` | catalog | authoritative | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `attachment_registry` | catalog | authoritative | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `taxonomy_nodes` | catalog (taxonomy) | authoritative | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `taxonomy_snapshot_nodes` | catalog (taxonomy) | snapshot | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `attribute_definitions` | catalog | authoritative (versioned) | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `attribute_schemas` | catalog | authoritative (versioned) | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `id_sequences` | catalog | operational (counter) | durable (counters) | none | retain: counters must never be reset | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `price_current` | pricing | authoritative | durable | none | retain (current commercial state); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `price_events` | pricing/catalog | event (two shapes) | durable (two row shapes) | none | TARGET: retain (R1). **CURRENT: rolled rows are hard-deleted hourly by default — NON-COMPLIANT with R1** | none known | n/a (no personal data) | TBD — PRODUCTION POLICY | included |
| `price_rollups` | catalog | derived | derived (rebuildable) | none | no retention need: rebuildable | none known | n/a (no personal data) | n/a | included but rebuildable; a restore may omit it and rebuild |
| `inventory` | inventory | authoritative | durable | none | retain (current commercial state); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `inventory_reservations` | inventory | authoritative | durable | none | TBD — PRODUCTION POLICY | none known | n/a (no personal data) | TBD — PRODUCTION POLICY | included |
| `media_refs` | media | authoritative | durable | none | retain (current commercial state); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `service_areas` | serviceability | authoritative | durable | none | retain (current commercial state); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `product_card_base` | commerce read | derived (rebuildable) | derived (rebuildable) | none | no retention need: rebuildable | none known | n/a (no personal data) | n/a | included but rebuildable; a restore may omit it and rebuild |
| `consumer_projection_policy` | catalog consumer | authoritative (config) | durable | none | retain (versioned reference data); no deletion path | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |
| `node_events` | catalog (taxonomy) | event | durable (audit ledger) | none (asserted by `IndexContractIT`) | UNDECIDED / DEPLOYMENT POLICY; no TTL and no deletion; tiering is a DB-6 decision | **staff identifier**: `actor.id` (`google:<subject>` / `service:<role>` / `system:<name>`), `actor.credential_id`, `actor.request_id`; free-form `detail` is not schema-constrained | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `domain_events` | common/audit | event | durable (audit ledger) | none (asserted by `IndexContractIT`) | UNDECIDED / DEPLOYMENT POLICY; no TTL and no deletion; tiering is a DB-6 decision | **staff identifier**: `actor.id` (`google:<subject>` / `service:<role>` / `system:<name>`), `actor.credential_id`, `actor.request_id`; free-form `detail` is not schema-constrained | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `rollup_state` | — (unused) | — | unused | none | n/a: collection retirement decision pending (`DATABASE_INDEX_MANIFEST.md` §6.2, runbook §14) | none known | n/a (no personal data) | n/a | included |
| `customer_otp_challenges` | auth.otp | operational | temporary | `expiresAt_1` (0 s) and `otp_challenge_createdat_backstop_ttl` (86,400 s on `createdAt`) | temporary: removed by TTL | **DIRECT PII**: `phoneNormalized`; **credential-derived**: `otpVerifier` (HMAC of the code; the code itself is never stored) | removed by TTL when expired; no erasure-on-request path | n/a | included; expired rows persist in a backup until the TTL monitor has deleted them from the source, and can reappear in a restored copy until it runs again |
| `customer_otp_verified_grants` | auth.otp/session | operational | temporary | `expiresAt_1` (0 s) | temporary: removed by TTL | **DIRECT PII**: `phoneNormalized` | removed by TTL when expired; no erasure-on-request path | n/a | included; expired rows persist in a backup until the TTL monitor has deleted them from the source, and can reappear in a restored copy until it runs again |
| `customers` | auth.session | authoritative | durable | none | TBD — PRODUCTION POLICY | **DIRECT PII**: `phoneNormalized` | no erasure/anonymisation path exists in the backend; design: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included — **a restore re-introduces erased personal data** |
| `customer_sessions` | auth.session | authoritative | temporary | `session_expiry_ttl` (0 s; cleanup only — the application's own `expiresAt`/`revokedAt` checks are authoritative) | temporary: removed by TTL | **credential-derived**: `refreshTokenDigest` (HMAC; the token itself is never stored); customer link: `customerId` | removed by TTL when expired | n/a | included; expired rows persist in a backup until the TTL monitor has deleted them from the source, and can reappear in a restored copy until it runs again |
| `customer_profiles` | customer.profile | authoritative | durable | none | TBD — PRODUCTION POLICY | **DIRECT PII**: `displayName`, `email`; customer link: `_id` = `customerId` | no erasure/anonymisation path exists in the backend; design: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included — **a restore re-introduces erased personal data** |
| `customer_addresses` | customer.address | authoritative | durable | none | TBD — PRODUCTION POLICY | **DIRECT PII**: `recipientName`, `recipientPhone`, `addressLine1`, `addressLine2`, `landmark`, `city`, `state`, `postalCode`, `latitude`, `longitude`; customer link: `customerId` | no erasure/anonymisation path exists in the backend (a customer can delete their own address via the API); design: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included — **a restore re-introduces erased personal data** |
| `customer_address_state` | customer.address | authoritative (control) | durable | none | TBD — PRODUCTION POLICY | customer link: `_id` = `customerId`, `defaultAddressId` | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `customer_carts` | customer.cart | authoritative | durable | none | TBD — PRODUCTION POLICY | customer link: `_id` = `customerId` | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `checkout_quotes` | customer.checkout | snapshot | durable | none | TBD — PRODUCTION POLICY | customer link: `customerId`, `addressId` (the raw `Idempotency-Key` is never stored, only its SHA-256) | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `orders` | customer.order | authoritative + snapshot | durable | none | TBD — PRODUCTION POLICY | **DIRECT PII**: `addressSnapshot.*` (`recipientName`, `recipientPhone`, address lines, `landmark`, `city`, `state`, `postalCode`, coordinates); customer link: `customerId` | no erasure/anonymisation path exists in the backend; design: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included — **a restore re-introduces erased personal data** |
| `memberships` | membership | authoritative | durable | none | TBD — PRODUCTION POLICY | customer link: `customerId` | linked data: TBD — PRODUCTION POLICY | TBD — PRODUCTION POLICY | included |
| `content_blocks` | content | authoritative (config) | durable | none | retain (versioned merchandising config); never deleted, archived instead | none known | n/a (no personal data) | n/a (reference/catalogue data) | included |

## 3. Personal-data map (summary)

| Class | Collections | Fields |
|---|---|---|
| **DIRECT PII** | `customers`, `customer_profiles`, `customer_addresses`, `customer_otp_challenges`, `customer_otp_verified_grants`, `orders` | phone (`+91…`), display name, e-mail (lower-cased), recipient name/phone, address lines, landmark, city, state, postal code, latitude/longitude; the order's `addressSnapshot` is an immutable copy |
| **credential-derived** | `customer_otp_challenges` (`otpVerifier`), `customer_sessions` (`refreshTokenDigest`) | HMAC digests; neither the OTP nor the refresh token is stored |
| **customer link** | `customer_sessions`, `customer_address_state`, `customer_carts`, `checkout_quotes`, `memberships`, `orders`, `customer_profiles`, `customer_addresses` | `customerId` |
| **staff identifier** | `product_events`, `node_events`, `domain_events` | `actor.id`, `actor.credential_id`, `actor.request_id`; the audit-read API exposes these and never the free-form `detail` |
| none known | all catalogue, taxonomy, pricing, inventory, media, serviceability, reference and derived collections | — |

Rules that hold today (verified against the code):

- No password, token, API key or connection string is stored as an ordinary field. Secrets live outside the database (SSM, §`DATABASE_STAGING_RUNBOOK.md`).
- Raw OTP codes, refresh tokens and `Idempotency-Key` values are **never persisted**; only keyed digests are.
- **There is no erasure or anonymisation path** for a customer anywhere in the backend. The only delete paths in `main` are: offer merge (`MergeService`), the price rollup purge (`RollupService`, R1), derived-projection and rebuild-queue clean-up, and a customer deleting their **own address**. An erasure/retention design (including backups and the audit ledgers) is `TBD — PRODUCTION POLICY`.
- The `detail` map of an audit event is free-form and is not constrained by any schema; the audit-read API never reads it.

## 4. Migration bookkeeping collections

| Collection | Purpose | Personal data | Retention |
|---|---|---|---|
| `schema_migrations` | one document per migration id (status, checksum, operator label, build, sanitized error) | none (the operator field is a deployment-job label) | never deleted by hand; a corrective migration is a new one |
| `schema_migration_lock` | single-runner lease | none | one document, overwritten |

## 5. What this document does not decide

Retention periods for orders, quotes, memberships, customer data and audit ledgers; legal hold; privacy-deletion handling and its interaction with backups; tiering of the event ledgers; retirement of the seven unused collections; the R1 outcome for `price_events`. Each is `TBD — PRODUCTION POLICY` above and belongs to DB-6 and the business/legal owners.
