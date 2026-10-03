package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates all collections + validators + indexes idempotently.
 * The products validator is the Java rendering of docs/contract_attack.js — the validator
 * that was executed and verified against MongoDB 7 (26/26). Semantics must not drift.
 * Registered language list [en, hi] and the empty ext {} registry are the generated parts.
 */
@Component
public class SchemaBootstrap {

    public static final List<String> COLLECTIONS = List.of(
            "products", "gtin_registry", "identity_keys", "canonical_keys",
            "discriminating_attributes", "brands",
            "product_events", "classification_history", "evidence", "evidence_links",
            "work_queue", "offers_current", "catalogue_releases",
            "batches", "campaigns", "campaign_membership", "aliases", "variant_groups",
            "marketplace_crosswalks", "system_config", "attachment_registry",
            "price_events", "price_rollups", "rollup_state",
            // PR-03 Pricing foundation: canonical resolved current-price SoT per SKU (paise, MRP,
            // version, effective window). ADDITIVE and separate from offers_current, which remains
            // the raw multi-source commercial input; the two have distinct roles, not dual SoT.
            "price_current",
            // PR-04 Inventory foundation (ADR-004): authoritative stock per
            // (sku_id, fulfillment_location_id). available/stock_state are DERIVED from the two
            // persisted counters (on_hand, reserved), never stored. offers_current.available (a
            // per-source boolean observation) is a different fact and untouched.
            "inventory",
            // PR-05 Media foundation: canonical media REFERENCES per (owner_type, owner_id) —
            // storage-neutral asset keys, ordering, single-PRIMARY rule; public URLs are DERIVED
            // by configuration at read time. Distinct from attachment_registry (Law-4b validator
            // key governance) and from evidence payload_ref (evidence bytes) — no shared ownership.
            "media_refs",
            // PR-06 Serviceability foundation: pincode -> service area + INTERNAL fulfillment
            // routing, one document per PIN with embedded prioritised route candidates.
            "service_areas",
            // PR-06 neutral audit rail (domain_events): C-3-style audit for aggregates that are
            // NOT products (first user: service_area). product_events stays product-only.
            "domain_events",
            // PR-07 ProductCardBaseProjection: DERIVED, DISPOSABLE, location-agnostic read model
            // (Catalog identity + canonical Pricing + Media asset refs). Not a source of truth;
            // rebuildable at any time; NOT LIVE until the enrichment PR wires freshness.
            "product_card_base",
            "taxonomy_nodes", "attribute_definitions", "attribute_schemas",
            "node_events", "taxonomy_snapshot_nodes", "id_sequences",
            // RESP-PROJ RP-6: consumer PRESENTATION policy, keyed per vertical. Deliberately
            // separate from the governance registry — it changes nothing about attribute
            // semantics, validation or identity. Absence is a valid state (RP-6b).
            "consumer_projection_policy",
            // PR-11B: OTP challenge lifecycle (customer auth). Ephemeral by design; TTL-indexed.
            "customer_otp_challenges",
            // PR-11B: one-time login grant produced by a successful OTP verification. PR-11C's
            // exclusive consumption contract; ephemeral, TTL-indexed.
            "customer_otp_verified_grants",
            // PR-11C: minimal customer identity (phoneNormalized is canonical identity). No
            // profile fields — those belong to a future PR.
            "customers",
            // PR-11C: customer login session + refresh-token verifier. expiresAt/revokedAt are
            // APPLICATION predicates; the TTL index below is cleanup only, never authorization.
            "customer_sessions",
            // PR-12A: customer-owned editable profile (displayName/email only). Keyed by
            // customerId as _id -- no separate customerId index needed. Never phoneNormalized,
            // session state, or anything auth owns; a distinct domain from "customers" (auth
            // identity) by design.
            "customer_profiles",
            // PR-12B: one document per saved delivery address, _id the opaque ADDR_* id. Never
            // stores isDefault or any serviceability truth -- see customer_address_state.
            "customer_addresses",
            // PR-12B: ONE document per customer -- addressCount (the address-limit CAS) and
            // defaultAddressId (the single-default pointer). Both concurrency invariants this
            // domain needs are enforced entirely through atomic writes to this ONE document per
            // customer; see CustomerAddressStateRepository's class-level rationale.
            "customer_address_state",
            // PR-12C: ONE cart per customer (customerId as _id, so no extra index). Deliberately NO
            // TTL index: deleting an expired cart would reset its logical version to 0 and let a stale
            // client re-create over newer history. Expiry is an explicit runtime state transition that
            // clears items and ADVANCES the version; physical cleanup is deferred to a future design
            // that preserves version monotonicity (e.g. archival).
            "customer_carts",
            // PR-13A: immutable checkout quotes. Deliberately NO TTL index: an expired quote must stay
            // distinguishable from an unknown one (GET returns 410, not 404) and expiry is derived
            // from expiresAt. Unique (customerId, idempotencyKeyDigest) below is the concurrent
            // same-key create guard.
            "checkout_quotes",
            // PR-14A: immutable inventory reservation headers. Deliberately NO TTL index: an
            // expired-but-not-yet-reconciled reservation must stay inspectable and distinguishable
            // from one that never existed. Unique orderId below is BOTH the one-reservation-per-order
            // invariant and the concurrent-create race guard; expiry reconciliation always goes
            // through the same idempotent release() lifecycle, never a direct bulk edit.
            "inventory_reservations",
            // PR-14B: immutable Order Foundation documents, _id the opaque ORD_* id. Deliberately NO
            // status/customer-history index and NO TTL in this PR -- no query needs one yet. Unique
            // (customerId, quoteId) below is BOTH the structural one-quote-to-one-order invariant AND
            // the concurrent-create race guard, the same idiom checkout_quotes/inventory_reservations
            // already establish.
            "orders",
            // PR-16A-1: one document per Membership TERM (entitlement window under one immutable plan
            // version), _id the opaque MBR_* id. Deliberately NO TTL, NO customer-history index and NO
            // validUntil expiry-scan index -- no query needs one yet; each arrives with its query.
            "memberships");

    /**
     * PAG-2-SORT-1 transport support: the equality prefix the consumer-eligibility predicate uses,
     * terminated by {@code _id} so a keyset cursor resumes from the index rather than an in-memory
     * sort. {@code _id} is LAST and that order is part of the contract, not a formatting choice.
     */
    public static final List<String> PAG2_PRODUCT_INDEX_KEYS = List.of(
            "classification.vertical_id", "lifecycle", "classification.status", "_id");

    /** DB-2: explicit name of the {@code (classification.vertical_id, _id)} per-vertical scan index. */
    public static final String PRODUCT_VERTICAL_CURSOR_INDEX = "product_vertical_id_cursor";

    /**
     * The pre-PAG-2 index. It is an exact PREFIX of the above, so a compound index on
     * PAG2_PRODUCT_INDEX_KEYS serves every query this one served — it is redundant once the wider
     * index exists, and MongoDB does NOT widen an index in place. Leaving both behind would pay
     * write amplification for nothing, so bootstrap drops it.
     */
    static final List<String> LEGACY_PRODUCT_INDEX_KEYS = List.of(
            "classification.vertical_id", "lifecycle", "classification.status");

    /**
     * The ONLY fields {@code listIndexes()} reports for the historical plain index. OBSERVED, not
     * assumed — MongoDB 7.0.40 returns exactly {@code {"v": 2, "key": {...}, "name": "..."}} for
     * it (no {@code ns}; that field was dropped from the output in 4.4). Any other field on an index
     * document means the index carries behaviour the old bootstrap never gave it.
     */
    static final java.util.Set<String> LEGACY_INDEX_METADATA = java.util.Set.of("v", "key", "name");

    /**
     * Phase 3B traversal support. SnapshotTaxonomyReader's hot predicate is
     * {@code release_id = R AND parent_id IN (...)}; the unique {@code (release_id, node_id)} index
     * serves node() but cannot seek by parent, so without this every children/subtree query would
     * filter the whole release snapshot. ADDITIVE — the unique index stays.
     */
    public static final List<String> SNAPSHOT_TRAVERSAL_INDEX_KEYS = List.of("release_id", "parent_id");

    /**
     * Creates every collection in {@link #COLLECTIONS} that is absent (and only those). A NEW {@code products}
     * collection gets the strict $jsonSchema validator; an existing collection is never altered. Idempotent.
     * Shared by {@link #bootstrap} and the baseline migration.
     */
    public void ensureCollections(MongoDatabase db) {
        List<String> existing = db.listCollectionNames().into(new java.util.ArrayList<>());

        if (!existing.contains("products")) {
            db.createCollection("products", new CreateCollectionOptions().validationOptions(
                    new ValidationOptions()
                            .validator(new Document("$jsonSchema", productsSchema()))
                            .validationLevel(ValidationLevel.STRICT)
                            .validationAction(ValidationAction.ERROR)));
        }
        for (String name : COLLECTIONS) {
            if (!name.equals("products") && !existing.contains(name)) {
                db.createCollection(name);
            }
        }
    }

    /** True when the superseded pre-PAG-2 plain products index (exact legacy shape only) is present. */
    public boolean hasSupersededProductsPrefixIndex(MongoDatabase db) {
        if (!db.listCollectionNames().into(new java.util.ArrayList<>()).contains("products")) return false;
        for (Document index : db.getCollection("products").listIndexes()) {
            if (isHistoricalPlainPrefix(index, LEGACY_PRODUCT_INDEX_KEYS)) return true;
        }
        return false;
    }

    /** Drops ONLY the exact legacy-shaped prefix index; any lookalike is left alone. Idempotent. */
    public void dropSupersededProductsPrefixIndex(MongoDatabase db) {
        dropHistoricalPlainPrefixIndex(db.getCollection("products"), LEGACY_PRODUCT_INDEX_KEYS);
    }

    public void bootstrap(MongoDatabase db) {
        ensureCollections(db);

        // PAG-2-SORT-1. Create the wider index FIRST, then drop the prefix it supersedes, so
        // there is never a window in which neither exists.
        db.getCollection("products").createIndex(Indexes.ascending(PAG2_PRODUCT_INDEX_KEYS));
        dropHistoricalPlainPrefixIndex(db.getCollection("products"), LEGACY_PRODUCT_INDEX_KEYS);
        // The per-vertical `_id`-ordered scan index (product_vertical_id_cursor, DB-2) is NOT created here: it is
        // owned by the explicit migration V0002, whose preflight handles an existing index under another name or
        // with a conflicting definition instead of failing startup with IndexOptionsConflict (R5).
        db.getCollection("products").createIndex(
                Indexes.ascending("bundle_contents.component_product_id"), new IndexOptions().sparse(true));
        db.getCollection("products").createIndex(
                Indexes.ascending("variant_group_id"), new IndexOptions().sparse(true));
        db.getCollection("offers_current").createIndex(
                Indexes.ascending("product_id", "source", "seller", "channel"), new IndexOptions().unique(true));
        db.getCollection("canonical_keys").createIndex(Indexes.ascending("product_id"));
        db.getCollection("evidence_links").createIndex(Indexes.ascending("evidence_id", "active"));
        db.getCollection("evidence_links").createIndex(Indexes.ascending("product_id", "link_type"));
        db.getCollection("classification_history").createIndex(
                Indexes.ascending("product_id", "decided_at"));
        db.getCollection("product_events").createIndex(Indexes.ascending("product_id", "at"));
        db.getCollection("work_queue").createIndex(Indexes.ascending("status", "type"));
        db.getCollection("batches").createIndex(
                Indexes.ascending("product_id", "lot_no"), new IndexOptions().unique(true));
        db.getCollection("campaign_membership").createIndex(
                Indexes.ascending("campaign_id", "product_id"), new IndexOptions().unique(true));
        db.getCollection("aliases").createIndex(
                Indexes.ascending("alias_norm", "lang", "region"), new IndexOptions().unique(true));
        db.getCollection("price_events").createIndex(Indexes.ascending("product_id", "ts"));
        db.getCollection("price_events").createIndex(Indexes.ascending("rolled", "ts"));
        // PR-03: canonical current price, one active row per (SKU, currency). Additive; does not
        // touch offers_current's (product_id, source, seller, channel) unique index.
        db.getCollection("price_current").createIndex(
                Indexes.ascending("sku_id", "currency"), new IndexOptions().unique(true));
        // PR-04: canonical inventory, exactly one row per (SKU, fulfillment location). The unique
        // index is also the create-race guard. Additive; no other index needed — every access
        // path in PR-04 is a point lookup on this exact key.
        db.getCollection("inventory").createIndex(
                Indexes.ascending("sku_id", "fulfillment_location_id"), new IndexOptions().unique(true));
        // PR-05: exactly one canonical media set per owner; unique index doubles as the
        // create-race guard. All access is a point lookup on this key — no other index.
        db.getCollection("media_refs").createIndex(
                Indexes.ascending("owner_type", "owner_id"), new IndexOptions().unique(true));
        // PR-06: exactly one routing config per PIN; unique index doubles as the create-race
        // guard. service_area_id is deliberately NOT unique (a future area may span many PINs).
        db.getCollection("service_areas").createIndex(
                Indexes.ascending("pincode"), new IndexOptions().unique(true));
        // PR-06: neutral audit rail lookups by aggregate + time.
        db.getCollection("domain_events").createIndex(
                Indexes.ascending("aggregate_type", "aggregate_id", "at"));
        // PR-07: one derived card per SKU; unique index doubles as the create-race guard.
        // All PR-07 access is a point lookup on sku_id — no other index is justified yet.
        db.getCollection("product_card_base").createIndex(
                Indexes.ascending("sku_id"), new IndexOptions().unique(true));
        db.getCollection("taxonomy_nodes").createIndex(Indexes.ascending("parent_id"));
        db.getCollection("taxonomy_nodes").createIndex(Indexes.ascending("node_type", "status"));
        db.getCollection("attribute_definitions").createIndex(
                Indexes.ascending("key", "version"), new IndexOptions().unique(true));
        db.getCollection("attribute_schemas").createIndex(
                Indexes.ascending("schema_id", "version"), new IndexOptions().unique(true));
        db.getCollection("node_events").createIndex(Indexes.ascending("node_id", "at"));
        db.getCollection("consumer_projection_policy").createIndex(
                Indexes.ascending("vertical_id"), new IndexOptions().unique(true));
        db.getCollection("taxonomy_snapshot_nodes").createIndex(
                Indexes.ascending("release_id", "node_id"), new IndexOptions().unique(true));
        db.getCollection("taxonomy_snapshot_nodes").createIndex(
                Indexes.ascending(SNAPSHOT_TRAVERSAL_INDEX_KEYS));
        // Freeze semantics: at most ONE release may be open (publishing OR freezing) at a
        // time — enforced on the constant `gate` marker, cleared only on activation.
        db.getCollection("catalogue_releases").createIndex(Indexes.ascending("gate"),
                new IndexOptions().unique(true).partialFilterExpression(
                        new Document("gate", "OPEN")));
        db.getCollection("price_rollups").createIndex(
                Indexes.ascending("product_id", "seller"), new IndexOptions().unique(true));
        // PR-11B (hardening pass): TWO independent partial-unique guards, not one. "delivering" is
        // present ONLY while a challenge is PENDING_DELIVERY (at most one delivery attempt in
        // flight per phone+purpose); "active" is present ONLY while a challenge is ACTIVE (at most
        // one guessable code per phone+purpose). Deliberately separate: a resend's replacement
        // challenge occupies the "delivering" slot WITHOUT touching the previous ACTIVE code, so a
        // failed resend never destroys a working code. Same create-race-guard idiom as
        // service_areas.pincode and catalogue_releases.gate above.
        db.getCollection("customer_otp_challenges").createIndex(
                Indexes.ascending("phoneNormalized", "purpose"),
                new IndexOptions().name("otp_one_delivering_per_phone").unique(true)
                        .partialFilterExpression(new Document("delivering", true)));
        db.getCollection("customer_otp_challenges").createIndex(
                Indexes.ascending("phoneNormalized", "purpose"),
                new IndexOptions().name("otp_one_active_per_phone").unique(true)
                        .partialFilterExpression(new Document("active", true)));
        // Cleanup only — application logic enforces expiry itself (markExpiredIfPastDeadline);
        // this TTL sweep is asynchronous and must never be the sole expiry mechanism. NOTE:
        // expiresAt is null until activateAfterDelivery finalizes it (durability §6 — the OTP's
        // validity window starts at confirmed delivery, not creation), and the Mongo TTL monitor
        // never expires a null/missing date field — so a PENDING_DELIVERY/DELIVERY_FAILED document
        // that never reaches ACTIVE would live forever under this index alone.
        db.getCollection("customer_otp_challenges").createIndex(
                Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        // Backstop cleanup net for exactly that gap: createdAt is ALWAYS set, so every document —
        // however it ends its life — is swept within a day regardless of whether expiresAt was ever
        // populated. Generous window: this is cleanup only, never a business-logic deadline.
        db.getCollection("customer_otp_challenges").createIndex(
                Indexes.ascending("createdAt"), new IndexOptions().name("otp_challenge_createdat_backstop_ttl")
                        .expireAfter(1L, java.util.concurrent.TimeUnit.DAYS));
        // PR-11B: one-time login grant, cleaned up on the same TTL discipline. consume() enforces
        // expiry/one-time-use atomically; this index is cleanup only.
        db.getCollection("customer_otp_verified_grants").createIndex(
                Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        // PR-11B (hardening §7): defense-in-depth — _id uniqueness alone protects grantId, not
        // challengeId. This structurally forbids a second grant document ever being created for the
        // same challenge, even if application logic regressed.
        db.getCollection("customer_otp_verified_grants").createIndex(
                Indexes.ascending("challengeId"), new IndexOptions().unique(true));
        // PR-11C: exactly one customer per canonical phone; the unique index doubles as the
        // create-race guard for CustomerRepository.resolveOrCreate's upsert (the SAME idiom as
        // service_areas.pincode/catalogue_releases.gate above).
        db.getCollection("customers").createIndex(
                Indexes.ascending("phoneNormalized"), new IndexOptions().name("customer_one_per_phone").unique(true));
        // PR-11C: session lookup by owning customer (not required by any current query, but a
        // reasonable defensive index — a future logout-all/session-listing feature will need it,
        // and it costs nothing on this low-write-volume collection).
        db.getCollection("customer_sessions").createIndex(
                Indexes.ascending("customerId"), new IndexOptions().name("session_by_customer"));
        // Cleanup only — CustomerSessionRepository enforces expiresAt/revokedAt as APPLICATION
        // predicates on every authorization-relevant query; this TTL sweep is asynchronous and is
        // never itself the authorization mechanism.
        db.getCollection("customer_sessions").createIndex(
                Indexes.ascending("expiresAt"), new IndexOptions().name("session_expiry_ttl")
                        .expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        // PR-12B: list-by-customer, newest-first, with a deterministic _id tiebreaker -- the SAME
        // shape AddressRepository.findAllByCustomer sorts by. No isDefault index: isDefault is not
        // a stored field on this collection (see customer_address_state).
        db.getCollection("customer_addresses").createIndex(
                Indexes.compoundIndex(Indexes.ascending("customerId"), Indexes.descending("updatedAt"),
                        Indexes.ascending("_id")),
                new IndexOptions().name("address_by_customer_updated"));
        // PR-13A: exactly one quote per (customer, Idempotency-Key digest) -- the structural guard
        // that makes concurrent same-key POSTs resolve to ONE durable quote identity.
        db.getCollection("checkout_quotes").createIndex(
                Indexes.ascending("customerId", "idempotencyKeyDigest"),
                new IndexOptions().name("checkout_quote_one_per_idempotency_key").unique(true));
        // PR-14A: exactly one reservation per order -- the structural guard that makes a concurrent
        // same-order reserve() race resolve to ONE durable reservation identity.
        db.getCollection("inventory_reservations").createIndex(
                Indexes.ascending("orderId"), new IndexOptions().name("inventory_reservation_one_per_order")
                        .unique(true));
        // Bounded scan for the expiry-reconciliation worker: RESERVED headers past their expiresAt.
        db.getCollection("inventory_reservations").createIndex(
                Indexes.ascending("status", "expiresAt"), new IndexOptions().name("inventory_reservation_expiry"));
        // PR-14B: exactly one Order per (customer, quote) -- the structural guard that makes a
        // concurrent same-(customerId, quoteId) createOrder race resolve to ONE durable Order.
        db.getCollection("orders").createIndex(
                Indexes.ascending("customerId", "quoteId"), new IndexOptions().name("order_one_per_quote")
                        .unique(true));
        // PR-16A-1: at most ONE open Membership term per customer. "openTerm" is the BSON boolean true
        // ONLY while a term is ACTIVE and is $unset (never false/null) on every terminal transition, so
        // the partial filter is structurally unambiguous -- the same partial-unique idiom as
        // otp_one_active_per_phone. This is a storage guarantee, not an application check-then-insert.
        db.getCollection("memberships").createIndex(
                Indexes.ascending("customerId"), new IndexOptions().name("membership_one_open_per_customer")
                        .unique(true).partialFilterExpression(new Document("openTerm", true)));
        // PR-16A-1: exactly one durable term per (grantSource, grantRef) -- the namespaced idempotency
        // key, so the same external reference can never create (or later extend) a term twice.
        db.getCollection("memberships").createIndex(
                Indexes.ascending("grantSource", "grantRef"),
                new IndexOptions().name("membership_one_per_grant_reference").unique(true));
        // PR-16A-2: the entitlement read is a LIFECYCLE query (customerId + status = ACTIVE), not the openTerm slot
        // query, so a corrupt ACTIVE row with a missing/malformed marker cannot hide behind the partial filter.
        // Deliberately NON-unique: status is lifecycle data and openTerm (above) remains the sole uniqueness
        // authority -- two competing uniqueness mechanisms would be worse than one. No TTL, no history sort.
        db.getCollection("memberships").createIndex(
                Indexes.ascending("customerId", "status"), new IndexOptions().name("membership_active_by_customer"));
    }

    /**
     * Drops the HISTORICAL plain prefix index and nothing else.
     *
     * <p>Identity is the key pattern — exact fields, exact order, every direction exactly 1 —
     * AND the absence of ANY field beyond the metadata {@code listIndexes()} reports for the plain
     * index the old bootstrap created. Unique, sparse, partial filter, TTL, collation, hidden, or
     * an option this code has never heard of: all of them mean the index was created deliberately
     * for some other purpose, and {@code dropIndex} is destructive, so it is LEFT ALONE. Key
     * equivalence is not semantic equivalence; the migration fails conservative.
     *
     * <p>Matched by pattern rather than by name: an index name is a generated implementation
     * detail, and hard-coding one would silently no-op against a database where the index was
     * created under a different name. Order is compared as a LIST, because {@code Document.equals}
     * is map equality and would treat a differently-ordered compound index as the same one.
     *
     * <p>Idempotent: on a database that has already migrated, nothing matches and nothing happens.
     */
    static void dropHistoricalPlainPrefixIndex(MongoCollection<Document> collection,
                                                List<String> keys) {
        List<String> doomed = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            if (isHistoricalPlainPrefix(index, keys)) {
                doomed.add(index.getString("name"));
            }
        }
        // Collected first: dropping while iterating the cursor would be undefined.
        for (String name : doomed) {
            collection.dropIndex(name);
        }
    }

    /** True only for an index that IS what the old bootstrap created — pattern and options. */
    public static boolean isHistoricalPlainPrefix(Document index, List<String> keys) {
        Document key = index.get("key", Document.class);
        if (key == null || !new ArrayList<>(key.keySet()).equals(keys)) {
            return false;
        }
        for (String field : keys) {
            // Exactly 1, not "truncates to 1": intValue() would accept 1.5 or 1.9.
            if (!(key.get(field) instanceof Number n) || n.doubleValue() != 1.0d) {
                return false;
            }
        }
        // WHITELIST, not denylist. A denylist proves only "none of the options we remembered";
        // it does not prove "this IS the old bootstrap index" — an index with the legacy keys
        // plus e.g. hidden:true would have slipped through. For a destructive drop the posture is
        // fail conservative: any field outside the observed metadata set means we do not
        // understand this index's semantics, and one temporarily redundant index is preferable to
        // deleting one we did not create. background:true is deliberately NOT special-cased.
        for (String field : index.keySet()) {
            if (!LEGACY_INDEX_METADATA.contains(field)) {
                return false;
            }
        }
        return true;
    }

    static Document productsSchema() {
        String json = """
        { "bsonType": "object", "additionalProperties": false,
          "required": ["_id","product_type","identity","brand_code","title","lifecycle",
                       "classification","attributes","attributes_meta","version","created_at"],
          "properties": {
            "_id": {"bsonType":"string","pattern":"^TZP-"},
            "product_type": {"enum":["single","variant_pack","bundle"]},
            "lifecycle": {"enum":["draft","active","merging","discontinued","archived","merged"]},
            "identity": {"bsonType":"object","additionalProperties":false,"required":["type"],
              "properties":{"type":{"enum":["gtin","internal"]},"internal_key":{"bsonType":["string","null"]},
                "canonical_key":{"bsonType":["string","null"]},
                "canonical_key_version":{"bsonType":["string","null"]}}},
            "gtins": {"bsonType":"array","maxItems":12,"items":{"bsonType":"object","additionalProperties":false,
              "required":["value"],"properties":{"value":{"bsonType":"string"},"market":{"bsonType":"string"},
              "valid_from":{"bsonType":"date"},"valid_to":{"bsonType":["date","null"]}}}},
            "brand_code": {"bsonType":"string"},
            "title": {"bsonType":"string"},
            "localized_titles": {"bsonType":"object","additionalProperties":false,
              "properties":{"en":{"bsonType":"string"},"hi":{"bsonType":"string"}}},
            "classification": {"bsonType":"object","additionalProperties":false,
              "required":["vertical_id","release_id","status"],
              "properties":{"vertical_id":{"bsonType":["string","null"]},
                "release_id":{"bsonType":"string"},
                "status":{"enum":["confirmed","provisional","review","scope_blocked"]},
                "confidence":{"bsonType":["double","null"],"minimum":0,"maximum":1},
                "method_detail":{"bsonType":"object"},
                "evidence_refs":{"bsonType":"array","maxItems":20,"items":{"bsonType":"string","pattern":"^EV-"}}}},
            "attributes": {"bsonType":"object"},
            "attributes_meta": {"bsonType":"object","required":["validated_release"],
              "properties":{"validated_release":{"bsonType":"string"}}},
            "attribute_provenance": {"bsonType":"object"},
            "bundle_contents": {"bsonType":["array","null"],"maxItems":100,"items":{"bsonType":"object",
              "additionalProperties":false,"required":["component_product_id","qty"],
              "properties":{"component_product_id":{"bsonType":"string","pattern":"^TZP-"},
                "qty":{"bsonType":"int","minimum":1},"vertical_id_snapshot":{"bsonType":"string"},
                "title_snapshot":{"bsonType":"string"},"gtin_snapshot":{"bsonType":["string","null"]}}}},
            "pack_of": {"bsonType":["object","null"],"additionalProperties":false,
              "required":["component_product_id","qty"],
              "properties":{"component_product_id":{"bsonType":"string","pattern":"^TZP-"},
                "qty":{"bsonType":"int","minimum":2}}},
            "browse_verticals": {"bsonType":["array","null"],"maxItems":120,"items":{"bsonType":"string"}},
            "variant_group_id": {"bsonType":["string","null"]},
            "formulation_version": {"bsonType":["int","null"]},
            "ext": {"bsonType":"object","additionalProperties":false,"properties":{}},
            "merged_into": {"bsonType":["string","null"]},
            "version": {"bsonType":"int","minimum":1},
            "created_at": {"bsonType":"date"},
            "updated_at": {"bsonType":["date","null"]}},
          "oneOf": [
            { "properties": { "product_type": {"enum":["single"]},
                "classification": {"properties":{"vertical_id":{"bsonType":"string"}}},
                "bundle_contents": {"bsonType":"null"},
                "pack_of": {"bsonType":"null"} } },
            { "required": ["pack_of"],
              "properties": { "product_type": {"enum":["variant_pack"]},
                "classification": {"properties":{"vertical_id":{"bsonType":"string"}}},
                "bundle_contents": {"bsonType":"null"},
                "pack_of": {"bsonType":"object"} } },
            { "properties": { "product_type": {"enum":["bundle"]},
                "classification": {"properties":{"vertical_id":{"bsonType":"null"}}},
                "bundle_contents": {"bsonType":"array","minItems":2},
                "pack_of": {"bsonType":"null"} } } ] }
        """;
        return Document.parse(json);
    }
}
