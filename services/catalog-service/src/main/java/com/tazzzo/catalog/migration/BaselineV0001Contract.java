package com.tazzzo.catalog.migration;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The FROZEN contract of released migration V0001 (the baseline schema): exactly the collections and the 48 indexes
 * that V0001 introduced, copied as literals. It is deliberately NOT derived from {@code SchemaBootstrap.COLLECTIONS}
 * or {@link IndexCatalog#BASELINE}: those describe the CURRENT schema and grow, but V0001's definition, and so the
 * checksum recorded in every migrated database's history, must never change. A later collection or index belongs in
 * a new migration (V0008 or later), never here.
 *
 * <p>Never edit these lists. {@code MigrationRegistryTest} pins V0001's checksum, and {@code BaselineFrozenContractTest}
 * pins the sizes and the independence from the live constants.
 */
final class BaselineV0001Contract {

    private BaselineV0001Contract() { }

    /** The 49 collections V0001 ensures, in the order V0001 recorded them. */
    static final List<String> COLLECTIONS = List.of(
            "products",
            "gtin_registry",
            "identity_keys",
            "canonical_keys",
            "discriminating_attributes",
            "brands",
            "product_events",
            "classification_history",
            "evidence",
            "evidence_links",
            "work_queue",
            "offers_current",
            "catalogue_releases",
            "batches",
            "campaigns",
            "campaign_membership",
            "aliases",
            "variant_groups",
            "marketplace_crosswalks",
            "system_config",
            "attachment_registry",
            "price_events",
            "price_rollups",
            "rollup_state",
            "price_current",
            "inventory",
            "media_refs",
            "service_areas",
            "domain_events",
            "product_card_base",
            "taxonomy_nodes",
            "attribute_definitions",
            "attribute_schemas",
            "node_events",
            "taxonomy_snapshot_nodes",
            "id_sequences",
            "consumer_projection_policy",
            "customer_otp_challenges",
            "customer_otp_verified_grants",
            "customers",
            "customer_sessions",
            "customer_profiles",
            "customer_addresses",
            "customer_address_state",
            "customer_carts",
            "checkout_quotes",
            "inventory_reservations",
            "orders",
            "memberships");

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

    /** The 48 indexes V0001 ensures. */
    static final List<IndexSpec> INDEXES;

    static {
        List<IndexSpec> b = new ArrayList<>();
        // products
        b.add(plain("products", k("classification.vertical_id", 1, "lifecycle", 1, "classification.status", 1, "_id", 1)));
        b.add(new IndexSpec("products", null, k("bundle_contents.component_product_id", 1), false, true, null, null));
        b.add(new IndexSpec("products", null, k("variant_group_id", 1), false, true, null, null));
        // catalogue
        b.add(uniq("offers_current", k("product_id", 1, "source", 1, "seller", 1, "channel", 1)));
        b.add(plain("canonical_keys", k("product_id", 1)));
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
        b.add(named("customer_sessions", "session_by_customer", k("customerId", 1), false, null, null));
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
        INDEXES = List.copyOf(b);
    }
}
