package com.tazzzo.catalog.migration;

import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.bson.Document;

import java.util.Comparator;
import java.util.List;

/**
 * The registry of migrations, in the order they run (sorted by id). Appending is the ONLY way to evolve the
 * database: an applied migration is never edited (its checksum is recorded and verified).
 */
public final class Migrations {

    private Migrations() { }

    public static List<Migration> defaults(SchemaBootstrap bootstrap, TaxonomyLoader loader) {
        return List.<Migration>of(
                new BaselineSchemaMigration(bootstrap),
                new CreateIndexMigration("V0002__products_vertical_id_cursor_index",
                        "products (classification.vertical_id, _id): per-vertical _id-ordered scan (DB-2); adopts an equivalent existing index",
                        List.of(IndexCatalog.PRODUCT_VERTICAL_CURSOR_SPEC), null),
                new TaxonomySeedMigration(loader),
                new TaxonomyPackFieldsDataMigration(loader),
                new CreateIndexMigration("V0005__evidence_links_unique_link",
                        "evidence_links unique (evidence_id, product_id, link_type): DB-enforces one link per evidence/product/type",
                        List.of(IndexCatalog.EVIDENCE_LINK_UNIQUE_SPEC),
                        DuplicateCheck.byFields("evidence_links", null, "evidence_id", "product_id", "link_type")),
                new CreateIndexMigration("V0006__taxonomy_nodes_unique_active_sibling_name",
                        "taxonomy_nodes partial unique (parent_id, name) where status=active: DB-enforces unique active sibling names",
                        List.of(IndexCatalog.TAXONOMY_SIBLING_UNIQUE_SPEC),
                        DuplicateCheck.byFields("taxonomy_nodes", new Document("status", "active"), "parent_id", "name")),
                new CreateIndexMigration("V0007__audit_read_partial_indexes",
                        "product_events/node_events/domain_events: nine partial audit-read indexes (recent, actor, request) for GET /api/v1/admin/audit-events (PR #49)",
                        IndexCatalog.AUDIT_READ_SPECS, null),
                new CreateIndexMigration("V0011__support_case_indexes",
                        "support_cases: by customer, by status and overall, newest-updated first (PR-O)",
                        IndexCatalog.SUPPORT_CASE_SPECS, null),
                new CreateIndexMigration("V0009__product_card_search_tokens_index",
                        "product_card_base (search_tokens, sku_id): multikey index for the public product search (PR-G)",
                        List.of(IndexCatalog.PRODUCT_CARD_SEARCH_SPEC), null),
                new CreateIndexMigration("V0008__delivery_slot_indexes",
                        "delivery_slot_windows (service_area_id) by-area lookup and delivery_slot_usage TTL on expire_at (PR-E delivery slots)",
                        IndexCatalog.DELIVERY_SLOT_SPECS, null),
                new CreateIndexMigration("V0010__orders_by_customer_recent_index",
                        "orders (customerId, createdAt desc, _id desc): the customer order history, newest first (PR-M)",
                        List.of(IndexCatalog.ORDER_BY_CUSTOMER_RECENT_SPEC), null),
                new CreateIndexMigration("V0012__orders_staff_queue_indexes",
                        "orders (status, createdAt desc, _id desc) and (createdAt desc, _id desc): the staff order queue (PR-M2)",
                        IndexCatalog.STAFF_ORDER_QUEUE_SPECS, null),
                // DROP-CANDIDATES: registered but DISABLED. They run only when named in tazzzo.migration.enabled-migrations
                // after the owner approves (docs/database/DATABASE_MIGRATION_RUNBOOK.md, unused-index decisions).
                new DropIndexMigration("V0101__drop_unused_session_by_customer_index",
                        "customer_sessions.session_by_customer: no reader in main (all session reads are _id-keyed)",
                        IndexCatalog.SESSION_BY_CUSTOMER_SPEC, false),
                new DropIndexMigration("V0102__drop_unused_canonical_keys_product_id_index",
                        "canonical_keys.product_id: never queried (reads are _id-keyed)",
                        IndexCatalog.CANONICAL_KEYS_PRODUCT_SPEC, false)
        ).stream().sorted(Comparator.comparing(Migration::id)).toList();
    }
}
