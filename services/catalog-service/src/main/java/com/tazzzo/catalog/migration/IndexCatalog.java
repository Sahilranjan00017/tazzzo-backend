package com.tazzzo.catalog.migration;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Executable form of docs/database/DATABASE_INDEX_MANIFEST.md.
 *
 * <p>{@link #BASELINE} is the set that {@code SchemaBootstrap.bootstrap} creates (the pre-migration
 * schema); {@link #MANAGED} is created ONLY by explicit migrations. A drift test pins {@code BASELINE}
 * against what {@code bootstrap} really creates, and {@code IndexContractIT} keeps an independent oracle.
 */
public final class IndexCatalog {

    public static final String PRODUCT_VERTICAL_CURSOR = com.tazzzo.catalog.schema.SchemaBootstrap.PRODUCT_VERTICAL_CURSOR_INDEX;
    public static final String EVIDENCE_LINK_UNIQUE = "evidence_link_one_per_evidence_product_type";
    public static final String TAXONOMY_SIBLING_UNIQUE = "taxonomy_node_one_active_per_parent_name";

    private IndexCatalog() { }

    private static LinkedHashMap<String, Integer> k(Object... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static IndexSpec plain(String c, LinkedHashMap<String, Integer> keys) {
        return new IndexSpec(c, null, keys, false, false, null, null);
    }

    private static IndexSpec uniq(String c, LinkedHashMap<String, Integer> keys) {
        return new IndexSpec(c, null, keys, true, false, null, null);
    }

    private static IndexSpec named(String c, String name, LinkedHashMap<String, Integer> keys, boolean unique,
                                   Document partial, Long ttl) {
        return new IndexSpec(c, name, keys, unique, false, partial, ttl);
    }

    public static final IndexSpec PRODUCT_VERTICAL_CURSOR_SPEC = named("products", PRODUCT_VERTICAL_CURSOR,
            k("classification.vertical_id", 1, "_id", 1), false, null, null);

    public static final IndexSpec EVIDENCE_LINK_UNIQUE_SPEC = named("evidence_links", EVIDENCE_LINK_UNIQUE,
            k("evidence_id", 1, "product_id", 1, "link_type", 1), true, null, null);

    public static final IndexSpec TAXONOMY_SIBLING_UNIQUE_SPEC = named("taxonomy_nodes", TAXONOMY_SIBLING_UNIQUE,
            k("parent_id", 1, "name", 1), true, new Document("status", "active"), null);

    public static final IndexSpec SESSION_BY_CUSTOMER_SPEC = named("customer_sessions", "session_by_customer",
            k("customerId", 1), false, null, null);

    public static final IndexSpec CANONICAL_KEYS_PRODUCT_SPEC = plain("canonical_keys", k("product_id", 1));

    /**
     * Admin audit-read (PR #49): three partial indexes on each of three ledgers, over attributed rows only. Names and
     * shapes mirror {@code SchemaBootstrap.createAuditReadIndexes}; the legacy bootstrap still creates them for
     * non-migrated (test/dev) databases, and V0007 creates them for migrated ones. Pinned equal by IndexContractIT.
     */
    public static final List<IndexSpec> AUDIT_READ_SPECS;

    static {
        Document attributed = new Document("actor", new Document("$type", "object"));
        List<IndexSpec> a = new ArrayList<>();
        for (String ledger : com.tazzzo.catalog.schema.SchemaBootstrap.AUDIT_READ_LEDGERS) {
            a.add(named(ledger, com.tazzzo.catalog.schema.SchemaBootstrap.AUDIT_IDX_RECENT,
                    k("at", -1, "_id", -1), false, attributed, null));
            a.add(named(ledger, com.tazzzo.catalog.schema.SchemaBootstrap.AUDIT_IDX_ACTOR,
                    k("actor.id", 1, "at", -1, "_id", -1), false, attributed, null));
            a.add(named(ledger, com.tazzzo.catalog.schema.SchemaBootstrap.AUDIT_IDX_REQUEST,
                    k("actor.request_id", 1, "at", -1, "_id", -1), false, attributed, null));
        }
        AUDIT_READ_SPECS = List.copyOf(a);
    }

    /**
     * Delivery slots (backend completion PR-E): the by-area lookup of window definitions and the TTL that purges a
     * per-occurrence capacity counter a week after its slot date. Migration-only (V0008): bootstrap never creates them,
     * which keeps the dev/test bootstrap equal to baseline + audit-read as IndexContractIT pins.
     */
    public static final IndexSpec DELIVERY_WINDOW_BY_AREA_SPEC = named("delivery_slot_windows", "delivery_window_by_area",
            k("service_area_id", 1), false, null, null);

    public static final IndexSpec DELIVERY_USAGE_TTL_SPEC = named("delivery_slot_usage", "delivery_usage_expiry_ttl",
            k("expire_at", 1), false, null, 0L);

    public static final List<IndexSpec> DELIVERY_SLOT_SPECS = List.of(DELIVERY_WINDOW_BY_AREA_SPEC, DELIVERY_USAGE_TTL_SPEC);

    /**
     * Customer order history (PR-M): the caller's own orders newest first, keyset-paged by {@code (createdAt, _id)}.
     * Migration-only (V0010); the existing unique {@code (customerId, quoteId)} index cannot serve the sort.
     */
    public static final IndexSpec ORDER_BY_CUSTOMER_RECENT_SPEC = named("orders", "order_by_customer_recent",
            k("customerId", 1, "createdAt", -1, "_id", -1), false, null, null);

    /** Indexes created only by explicit migrations (never by the baseline migration). */
    public static final List<IndexSpec> MANAGED;

    static {
        List<IndexSpec> m = new ArrayList<>(List.of(
                PRODUCT_VERTICAL_CURSOR_SPEC, EVIDENCE_LINK_UNIQUE_SPEC, TAXONOMY_SIBLING_UNIQUE_SPEC));
        m.addAll(AUDIT_READ_SPECS);
        m.addAll(DELIVERY_SLOT_SPECS);
        m.add(ORDER_BY_CUSTOMER_RECENT_SPEC);
        MANAGED = List.copyOf(m);
    }

    /** The 48 indexes {@code SchemaBootstrap.bootstrap} creates. */
    public static final List<IndexSpec> BASELINE;

    static {
        List<IndexSpec> b = new ArrayList<>();
        // products
        b.add(plain("products", k("classification.vertical_id", 1, "lifecycle", 1, "classification.status", 1, "_id", 1)));
        b.add(new IndexSpec("products", null, k("bundle_contents.component_product_id", 1), false, true, null, null));
        b.add(new IndexSpec("products", null, k("variant_group_id", 1), false, true, null, null));
        // catalogue
        b.add(uniq("offers_current", k("product_id", 1, "source", 1, "seller", 1, "channel", 1)));
        b.add(CANONICAL_KEYS_PRODUCT_SPEC);
        b.add(plain("evidence_links", k("evidence_id", 1, "active", 1)));
        b.add(plain("evidence_links", k("product_id", 1, "link_type", 1)));
        b.add(plain("classification_history", k("product_id", 1, "decided_at", 1)));
        b.add(plain("product_events", k("product_id", 1, "at", 1)));
        b.add(plain("work_queue", k("status", 1, "type", 1)));
        b.add(uniq("batches", k("product_id", 1, "lot_no", 1)));
        b.add(uniq("campaign_membership", k("campaign_id", 1, "product_id", 1)));
        b.add(uniq("aliases", k("alias_norm", 1, "lang", 1, "region", 1)));
        b.add(plain("price_events", k("product_id", 1, "ts", 1)));
        b.add(plain("price_events", k("rolled", 1, "ts", 1)));
        b.add(uniq("price_current", k("sku_id", 1, "currency", 1)));
        b.add(uniq("inventory", k("sku_id", 1, "fulfillment_location_id", 1)));
        b.add(uniq("media_refs", k("owner_type", 1, "owner_id", 1)));
        b.add(uniq("service_areas", k("pincode", 1)));
        b.add(plain("domain_events", k("aggregate_type", 1, "aggregate_id", 1, "at", 1)));
        b.add(uniq("product_card_base", k("sku_id", 1)));
        b.add(plain("taxonomy_nodes", k("parent_id", 1)));
        b.add(plain("taxonomy_nodes", k("node_type", 1, "status", 1)));
        b.add(uniq("attribute_definitions", k("key", 1, "version", 1)));
        b.add(uniq("attribute_schemas", k("schema_id", 1, "version", 1)));
        b.add(plain("node_events", k("node_id", 1, "at", 1)));
        b.add(uniq("consumer_projection_policy", k("vertical_id", 1)));
        b.add(uniq("taxonomy_snapshot_nodes", k("release_id", 1, "node_id", 1)));
        b.add(plain("taxonomy_snapshot_nodes", k("release_id", 1, "parent_id", 1)));
        b.add(named("catalogue_releases", "gate_1", k("gate", 1), true, new Document("gate", "OPEN"), null));
        b.add(uniq("price_rollups", k("product_id", 1, "seller", 1)));
        // auth / OTP (the ONLY TTL indexes)
        b.add(named("customer_otp_challenges", "otp_one_delivering_per_phone", k("phoneNormalized", 1, "purpose", 1), true,
                new Document("delivering", true), null));
        b.add(named("customer_otp_challenges", "otp_one_active_per_phone", k("phoneNormalized", 1, "purpose", 1), true,
                new Document("active", true), null));
        b.add(named("customer_otp_challenges", "expiresAt_1", k("expiresAt", 1), false, null, 0L));
        b.add(named("customer_otp_challenges", "otp_challenge_createdat_backstop_ttl", k("createdAt", 1), false, null, 86_400L));
        b.add(named("customer_otp_verified_grants", "expiresAt_1", k("expiresAt", 1), false, null, 0L));
        b.add(uniq("customer_otp_verified_grants", k("challengeId", 1)));
        // customer
        b.add(named("customers", "customer_one_per_phone", k("phoneNormalized", 1), true, null, null));
        b.add(SESSION_BY_CUSTOMER_SPEC);
        b.add(named("customer_sessions", "session_expiry_ttl", k("expiresAt", 1), false, null, 0L));
        b.add(named("customer_addresses", "address_by_customer_updated", k("customerId", 1, "updatedAt", -1, "_id", 1),
                false, null, null));
        // checkout / order / reservation / membership
        b.add(named("checkout_quotes", "checkout_quote_one_per_idempotency_key", k("customerId", 1, "idempotencyKeyDigest", 1),
                true, null, null));
        b.add(named("inventory_reservations", "inventory_reservation_one_per_order", k("orderId", 1), true, null, null));
        b.add(named("inventory_reservations", "inventory_reservation_expiry", k("status", 1, "expiresAt", 1), false, null, null));
        b.add(named("orders", "order_one_per_quote", k("customerId", 1, "quoteId", 1), true, null, null));
        b.add(named("memberships", "membership_one_open_per_customer", k("customerId", 1), true,
                new Document("openTerm", true), null));
        b.add(named("memberships", "membership_one_per_grant_reference", k("grantSource", 1, "grantRef", 1), true, null, null));
        b.add(named("memberships", "membership_active_by_customer", k("customerId", 1, "status", 1), false, null, null));
        BASELINE = List.copyOf(b);
    }

    /** Baseline plus migration-managed: the full index set of a fully migrated database. */
    public static List<IndexSpec> all() {
        List<IndexSpec> all = new ArrayList<>(BASELINE);
        all.addAll(MANAGED);
        return all;
    }
}
