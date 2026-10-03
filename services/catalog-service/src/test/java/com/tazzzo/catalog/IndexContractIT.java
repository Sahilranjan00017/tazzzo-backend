package com.tazzzo.catalog;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.WriteModel;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-2 index contract (docs/database/DATABASE_INDEX_MANIFEST.md). Pins, against a real MongoDB 7
 * replica set: exact index names, key order and direction, uniqueness, sparse, partial filters,
 * TTL values, the closed index set per collection, the rule that ONLY the four auth/OTP indexes
 * carry a TTL (no durable business collection may), absence of the superseded products index,
 * bootstrap idempotency, DB-level duplicate rejection, concurrent duplicate races, and the plan of
 * the one index added by DB-2.
 *
 * <p>The in-flight admin audit-read work adds {@code audit_read_*} indexes to the three event ledgers;
 * the closed-set check tolerates exactly that name prefix there (and nowhere else) so that work can
 * merge in either order without silently widening the contract.
 */
class IndexContractIT extends AbstractMongoIT {

    // ---- the manifest ------------------------------------------------------------------------

    /** One expected index. {@code name == null} means "Mongo's generated default name, derived from keys". */
    record Idx(String coll, String name, LinkedHashMap<String, Integer> keys, boolean unique, boolean sparse,
               Document partial, Long ttlSeconds) {
        String effectiveName() {
            if (name != null) return name;
            StringBuilder sb = new StringBuilder();
            keys.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "_").append(k).append('_').append(v));
            return sb.toString();
        }
    }

    private static LinkedHashMap<String, Integer> k(Object... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static Idx plain(String coll, LinkedHashMap<String, Integer> keys) {
        return new Idx(coll, null, keys, false, false, null, null);
    }

    private static Idx uniq(String coll, LinkedHashMap<String, Integer> keys) {
        return new Idx(coll, null, keys, true, false, null, null);
    }

    private static Idx named(String coll, String name, LinkedHashMap<String, Integer> keys, boolean unique,
                             Document partial, Long ttl) {
        return new Idx(coll, name, keys, unique, false, partial, ttl);
    }

    static final List<Idx> MANIFEST = List.of(
            // products (PAG-2 list index, DB-2 per-vertical cursor index, two sparse reference indexes)
            plain("products", k("classification.vertical_id", 1, "lifecycle", 1, "classification.status", 1, "_id", 1)),
            named("products", SchemaBootstrap.PRODUCT_VERTICAL_CURSOR_INDEX, k("classification.vertical_id", 1, "_id", 1),
                    false, null, null),
            new Idx("products", null, k("bundle_contents.component_product_id", 1), false, true, null, null),
            new Idx("products", null, k("variant_group_id", 1), false, true, null, null),
            // catalogue
            uniq("offers_current", k("product_id", 1, "source", 1, "seller", 1, "channel", 1)),
            plain("canonical_keys", k("product_id", 1)),
            plain("evidence_links", k("evidence_id", 1, "active", 1)),
            plain("evidence_links", k("product_id", 1, "link_type", 1)),
            plain("classification_history", k("product_id", 1, "decided_at", 1)),
            plain("product_events", k("product_id", 1, "at", 1)),
            plain("work_queue", k("status", 1, "type", 1)),
            uniq("batches", k("product_id", 1, "lot_no", 1)),
            uniq("campaign_membership", k("campaign_id", 1, "product_id", 1)),
            uniq("aliases", k("alias_norm", 1, "lang", 1, "region", 1)),
            plain("price_events", k("product_id", 1, "ts", 1)),
            plain("price_events", k("rolled", 1, "ts", 1)),
            uniq("price_current", k("sku_id", 1, "currency", 1)),
            uniq("inventory", k("sku_id", 1, "fulfillment_location_id", 1)),
            uniq("media_refs", k("owner_type", 1, "owner_id", 1)),
            uniq("service_areas", k("pincode", 1)),
            plain("domain_events", k("aggregate_type", 1, "aggregate_id", 1, "at", 1)),
            uniq("product_card_base", k("sku_id", 1)),
            plain("taxonomy_nodes", k("parent_id", 1)),
            plain("taxonomy_nodes", k("node_type", 1, "status", 1)),
            uniq("attribute_definitions", k("key", 1, "version", 1)),
            uniq("attribute_schemas", k("schema_id", 1, "version", 1)),
            plain("node_events", k("node_id", 1, "at", 1)),
            uniq("consumer_projection_policy", k("vertical_id", 1)),
            uniq("taxonomy_snapshot_nodes", k("release_id", 1, "node_id", 1)),
            plain("taxonomy_snapshot_nodes", k("release_id", 1, "parent_id", 1)),
            named("catalogue_releases", "gate_1", k("gate", 1), true, new Document("gate", "OPEN"), null),
            uniq("price_rollups", k("product_id", 1, "seller", 1)),
            // auth / OTP (the ONLY TTL indexes in the database)
            named("customer_otp_challenges", "otp_one_delivering_per_phone", k("phoneNormalized", 1, "purpose", 1), true,
                    new Document("delivering", true), null),
            named("customer_otp_challenges", "otp_one_active_per_phone", k("phoneNormalized", 1, "purpose", 1), true,
                    new Document("active", true), null),
            named("customer_otp_challenges", "expiresAt_1", k("expiresAt", 1), false, null, 0L),
            named("customer_otp_challenges", "otp_challenge_createdat_backstop_ttl", k("createdAt", 1), false, null, 86_400L),
            named("customer_otp_verified_grants", "expiresAt_1", k("expiresAt", 1), false, null, 0L),
            uniq("customer_otp_verified_grants", k("challengeId", 1)),
            // customer
            named("customers", "customer_one_per_phone", k("phoneNormalized", 1), true, null, null),
            named("customer_sessions", "session_by_customer", k("customerId", 1), false, null, null),
            named("customer_sessions", "session_expiry_ttl", k("expiresAt", 1), false, null, 0L),
            named("customer_addresses", "address_by_customer_updated", k("customerId", 1, "updatedAt", -1, "_id", 1),
                    false, null, null),
            // checkout / order / reservation / membership
            named("checkout_quotes", "checkout_quote_one_per_idempotency_key", k("customerId", 1, "idempotencyKeyDigest", 1),
                    true, null, null),
            named("inventory_reservations", "inventory_reservation_one_per_order", k("orderId", 1), true, null, null),
            named("inventory_reservations", "inventory_reservation_expiry", k("status", 1, "expiresAt", 1), false, null, null),
            named("orders", "order_one_per_quote", k("customerId", 1, "quoteId", 1), true, null, null),
            named("memberships", "membership_one_open_per_customer", k("customerId", 1), true,
                    new Document("openTerm", true), null),
            named("memberships", "membership_one_per_grant_reference", k("grantSource", 1, "grantRef", 1), true, null, null),
            named("memberships", "membership_active_by_customer", k("customerId", 1, "status", 1), false, null, null)
    );

    /** The only collections that may carry the in-flight audit-read indexes (name prefix below). */
    private static final Set<String> AUDIT_READ_LEDGERS = Set.of("product_events", "node_events", "domain_events");
    private static final String AUDIT_READ_PREFIX = "audit_read_";

    // ---- helpers -----------------------------------------------------------------------------

    private Map<String, Document> indexesOf(String coll) {
        Map<String, Document> out = new TreeMap<>();
        db.getCollection(coll).listIndexes().forEach(i -> out.put(i.getString("name"), i));
        return out;
    }

    private static LinkedHashMap<String, Integer> keyOf(Document index) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        Document key = index.get("key", Document.class);
        key.forEach((f, d) -> m.put(f, ((Number) d).intValue()));
        return m;
    }

    private String snapshotAllIndexes() {
        List<String> all = new ArrayList<>();
        for (String c : db.listCollectionNames()) {
            db.getCollection(c).listIndexes().forEach(i -> all.add(c + ":" + i.toJson()));
        }
        all.sort(String::compareTo);
        return String.join("\n", all);
    }

    private static void assertDuplicateKey(Runnable insert) {
        try {
            insert.run();
        } catch (MongoWriteException e) {
            assertThat(ErrorCategory.fromErrorCode(e.getError().getCode())).isEqualTo(ErrorCategory.DUPLICATE_KEY);
            return;
        }
        throw new AssertionError("expected a duplicate-key rejection but the insert succeeded");
    }

    /** First insert wins, second with the SAME unique key but a new _id is rejected; cleans up. */
    private void assertUniqueRejects(String coll, Supplier<Document> sameKeyDoc) {
        MongoCollection<Document> c = db.getCollection(coll);
        Document first = sameKeyDoc.get();
        Document second = sameKeyDoc.get();
        c.insertOne(first);
        try {
            assertDuplicateKey(() -> c.insertOne(second));
        } finally {
            // delete both ids: if the index were missing the second insert would have succeeded
            c.deleteMany(new Document("_id", new Document("$in", List.of(first.get("_id"), second.get("_id")))));
        }
    }

    /** 16 threads insert the same unique key concurrently: exactly one commits, the rest are duplicate-key. */
    private void assertRaceOneWinner(String coll, Supplier<Document> sameKeyDoc) throws Exception {
        MongoCollection<Document> c = db.getCollection(coll);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        List<Object> ids = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                Document d = sameKeyDoc.get();
                go.await();
                try {
                    c.insertOne(d);
                    ids.add(d.get("_id"));
                    ok.incrementAndGet();
                } catch (MongoWriteException e) {
                    if (ErrorCategory.fromErrorCode(e.getError().getCode()) == ErrorCategory.DUPLICATE_KEY) {
                        dup.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }
        try {
            go.countDown();
            for (Future<?> f : fs) f.get();
            assertThat(ok.get()).as(coll + " concurrent winners").isEqualTo(1);
            assertThat(dup.get()).as(coll + " concurrent duplicate-key rejections").isEqualTo(threads - 1);
        } finally {
            go.countDown();
            pool.shutdownNow();
            for (Object id : ids) c.deleteOne(new Document("_id", id));
        }
    }

    // ---- 1. exact spec of every expected index -----------------------------------------------

    @Test
    void every_manifest_index_exists_with_exact_name_keys_uniqueness_sparse_partial_and_ttl() {
        for (Idx e : MANIFEST) {
            Map<String, Document> actual = indexesOf(e.coll());
            assertThat(actual).as(e.coll() + " must have index " + e.effectiveName()).containsKey(e.effectiveName());
            Document a = actual.get(e.effectiveName());
            assertThat(keyOf(a)).as(e.coll() + "." + e.effectiveName() + " key order and direction")
                    .containsExactlyEntriesOf(e.keys());
            assertThat(Boolean.TRUE.equals(a.getBoolean("unique"))).as(e.effectiveName() + " unique").isEqualTo(e.unique());
            assertThat(Boolean.TRUE.equals(a.getBoolean("sparse"))).as(e.effectiveName() + " sparse").isEqualTo(e.sparse());
            assertThat(a.get("partialFilterExpression")).as(e.effectiveName() + " partial filter").isEqualTo(e.partial());
            Number ttl = (Number) a.get("expireAfterSeconds");
            assertThat(ttl == null ? null : ttl.longValue()).as(e.effectiveName() + " TTL seconds").isEqualTo(e.ttlSeconds());
            assertThat(a.get("collation")).as(e.effectiveName() + " collation").isNull();
            assertThat(a.get("hidden")).as(e.effectiveName() + " hidden").isNull();
        }
    }

    // ---- 2. closed set: nothing unexpected on any collection ---------------------------------

    @Test
    void no_collection_has_an_index_outside_the_manifest() {
        Map<String, Set<String>> expected = new TreeMap<>();
        for (Idx e : MANIFEST) expected.computeIfAbsent(e.coll(), x -> new TreeSet<>()).add(e.effectiveName());
        for (String coll : db.listCollectionNames()) {
            Set<String> actual = new TreeSet<>(indexesOf(coll).keySet());
            actual.remove("_id_");
            if (AUDIT_READ_LEDGERS.contains(coll)) {
                actual.removeIf(n -> n.startsWith(AUDIT_READ_PREFIX)); // in-flight audit-read indexes, tolerated here only
            }
            assertThat(actual).as("indexes on " + coll + " (besides _id_) must equal the manifest")
                    .isEqualTo(expected.getOrDefault(coll, new TreeSet<>()));
        }
    }

    // ---- 3. TTL is allowed ONLY on the four temporary auth/OTP indexes -----------------------

    @Test
    void only_the_four_temporary_auth_indexes_carry_a_ttl_and_no_durable_collection_does() {
        Set<String> ttl = new TreeSet<>();
        for (String coll : db.listCollectionNames()) {
            indexesOf(coll).forEach((name, spec) -> {
                if (spec.get("expireAfterSeconds") != null) ttl.add(coll + "." + name);
            });
        }
        assertThat(ttl).containsExactlyInAnyOrder(
                "customer_otp_challenges.expiresAt_1",
                "customer_otp_challenges.otp_challenge_createdat_backstop_ttl",
                "customer_otp_verified_grants.expiresAt_1",
                "customer_sessions.session_expiry_ttl");
        for (String durable : List.of("orders", "checkout_quotes", "memberships", "inventory_reservations",
                "customer_carts", "customer_profiles", "customer_addresses", "price_current", "price_events",
                "product_events", "node_events", "domain_events", "classification_history", "products", "inventory")) {
            indexesOf(durable).forEach((name, spec) ->
                    assertThat(spec.get("expireAfterSeconds")).as(durable + "." + name + " must not be TTL").isNull());
        }
    }

    // ---- 4. obsolete index absent; bootstrap idempotent --------------------------------------

    @Test
    void the_superseded_products_prefix_index_is_absent_and_the_wider_one_present() {
        Map<String, Document> p = indexesOf("products");
        for (Document spec : p.values()) {
            assertThat(keyOf(spec).keySet().stream().toList())
                    .as("legacy 3-key prefix index must not exist")
                    .isNotEqualTo(List.of("classification.vertical_id", "lifecycle", "classification.status"));
        }
        assertThat(p.values().stream().map(IndexContractIT::keyOf)
                .anyMatch(m -> List.copyOf(m.keySet()).equals(SchemaBootstrap.PAG2_PRODUCT_INDEX_KEYS))).isTrue();
    }

    @Test
    void bootstrap_is_idempotent_for_the_whole_index_set() {
        String before = snapshotAllIndexes();
        schemaBootstrap.bootstrap(db);
        schemaBootstrap.bootstrap(db);
        assertThat(snapshotAllIndexes()).isEqualTo(before);
    }

    // ---- 5. DB-level duplicate rejection for every unique index ------------------------------

    @Test
    void every_unique_index_rejects_a_duplicate_at_the_database() {
        assertUniqueRejects("customers", () -> new Document("phoneNormalized", "+919000000001"));
        assertUniqueRejects("customer_otp_verified_grants", () -> new Document("challengeId", "OTP_dup1"));
        assertUniqueRejects("checkout_quotes", () -> new Document("customerId", "CUS_a").append("idempotencyKeyDigest", "d1"));
        assertUniqueRejects("orders", () -> new Document("customerId", "CUS_a").append("quoteId", "CHKQ_a"));
        assertUniqueRejects("inventory_reservations", () -> new Document("orderId", "ORD_dup1"));
        assertUniqueRejects("memberships", () -> new Document("grantSource", "INTERNAL_GRANT").append("grantRef", "g-dup1"));
        assertUniqueRejects("price_current", () -> new Document("sku_id", "SKU-D1").append("currency", "INR"));
        assertUniqueRejects("inventory", () -> new Document("sku_id", "SKU-D1").append("fulfillment_location_id", "LOC-1"));
        assertUniqueRejects("media_refs", () -> new Document("owner_type", "SKU").append("owner_id", "SKU-D1"));
        assertUniqueRejects("service_areas", () -> new Document("pincode", "110001"));
        assertUniqueRejects("product_card_base", () -> new Document("sku_id", "SKU-D1"));
        assertUniqueRejects("offers_current", () -> new Document("product_id", "P").append("source", "s")
                .append("seller", "x").append("channel", "c"));
        assertUniqueRejects("aliases", () -> new Document("alias_norm", "dup").append("lang", "xx").append("region", "all"));
        assertUniqueRejects("attribute_definitions", () -> new Document("key", "dup_attr").append("version", 1));
        assertUniqueRejects("attribute_schemas", () -> new Document("schema_id", "dup_schema").append("version", 1));
        assertUniqueRejects("consumer_projection_policy", () -> new Document("vertical_id", "TZV-DUP"));
        assertUniqueRejects("taxonomy_snapshot_nodes", () -> new Document("release_id", "R-dup").append("node_id", "N1"));
        assertUniqueRejects("price_rollups", () -> new Document("product_id", "P").append("seller", "x"));
        assertUniqueRejects("batches", () -> new Document("product_id", "P").append("lot_no", "L1"));
        assertUniqueRejects("campaign_membership", () -> new Document("campaign_id", "C").append("product_id", "P"));
    }

    @Test
    void partial_unique_indexes_reject_only_rows_inside_their_filter() {
        // otp: at most one delivering and one active per (phone, purpose); rows outside the markers are free
        MongoCollection<Document> otp = db.getCollection("customer_otp_challenges");
        List<Object> ids = new ArrayList<>();
        try {
            Document d1 = new Document("phoneNormalized", "+919000000002").append("purpose", "LOGIN").append("delivering", true);
            otp.insertOne(d1); ids.add(d1.get("_id"));
            assertDuplicateKey(() -> otp.insertOne(new Document("phoneNormalized", "+919000000002")
                    .append("purpose", "LOGIN").append("delivering", true)));
            Document a1 = new Document("phoneNormalized", "+919000000002").append("purpose", "LOGIN").append("active", true);
            otp.insertOne(a1); ids.add(a1.get("_id")); // different marker: allowed
            assertDuplicateKey(() -> otp.insertOne(new Document("phoneNormalized", "+919000000002")
                    .append("purpose", "LOGIN").append("active", true)));
            for (int i = 0; i < 3; i++) { // no marker: unlimited history rows
                Document free = new Document("phoneNormalized", "+919000000002").append("purpose", "LOGIN");
                otp.insertOne(free); ids.add(free.get("_id"));
            }
        } finally {
            otp.deleteMany(new Document("_id", new Document("$in", ids)));
        }
        // memberships: at most one open term per customer even with DIFFERENT grant references; terminal rows
        // (openTerm absent) are unlimited
        MongoCollection<Document> mem = db.getCollection("memberships");
        List<Object> mids = new ArrayList<>();
        try {
            Document m1 = new Document("customerId", "CUS_open").append("openTerm", true)
                    .append("grantSource", "INTERNAL_GRANT").append("grantRef", "open-1");
            mem.insertOne(m1); mids.add(m1.get("_id"));
            assertDuplicateKey(() -> mem.insertOne(new Document("customerId", "CUS_open").append("openTerm", true)
                    .append("grantSource", "INTERNAL_GRANT").append("grantRef", "open-2")));
            for (int i = 0; i < 3; i++) { // terminal history rows: no openTerm marker
                Document t = new Document("customerId", "CUS_open").append("status", "EXPIRED")
                        .append("grantSource", "INTERNAL_GRANT").append("grantRef", "hist-" + i);
                mem.insertOne(t); mids.add(t.get("_id"));
            }
        } finally {
            mem.deleteMany(new Document("_id", new Document("$in", mids)));
        }
        // release gate: one OPEN release; cleared gate (activated) rows are unlimited
        MongoCollection<Document> rel = db.getCollection("catalogue_releases");
        List<Object> rids = new ArrayList<>();
        try {
            Document open = new Document("_id", "REL-OPEN-1").append("gate", "OPEN");
            rel.insertOne(open); rids.add("REL-OPEN-1");
            assertDuplicateKey(() -> rel.insertOne(new Document("_id", "REL-OPEN-2").append("gate", "OPEN")));
            for (int i = 0; i < 3; i++) {
                rel.insertOne(new Document("_id", "REL-DONE-" + i)); rids.add("REL-DONE-" + i); // no gate field
            }
            rel.updateOne(new Document("_id", "REL-OPEN-1"), new Document("$unset", new Document("gate", "")));
            rel.insertOne(new Document("_id", "REL-OPEN-3").append("gate", "OPEN")); rids.add("REL-OPEN-3");
        } finally {
            rel.deleteMany(new Document("_id", new Document("$in", rids)));
        }
    }

    // ---- 6. concurrent duplicate races --------------------------------------------------------

    @Test
    void concurrent_duplicate_inserts_have_exactly_one_winner_on_the_invariant_indexes() throws Exception {
        assertRaceOneWinner("customers", () -> new Document("phoneNormalized", "+919000000003"));
        assertRaceOneWinner("orders", () -> new Document("customerId", "CUS_race").append("quoteId", "CHKQ_race"));
        assertRaceOneWinner("checkout_quotes", () -> new Document("customerId", "CUS_race").append("idempotencyKeyDigest", "drace"));
        assertRaceOneWinner("inventory_reservations", () -> new Document("orderId", "ORD_race"));
        assertRaceOneWinner("price_current", () -> new Document("sku_id", "SKU-RACE").append("currency", "INR"));
        assertRaceOneWinner("catalogue_releases", () -> new Document("_id", new org.bson.types.ObjectId().toHexString())
                .append("gate", "OPEN"));
        assertRaceOneWinner("customer_otp_challenges", () -> new Document("phoneNormalized", "+919000000004")
                .append("purpose", "LOGIN").append("delivering", true));
        // distinct (grantSource, grantRef) per racer so ONLY the partial one-open-per-customer index can reject
        assertRaceOneWinner("memberships", () -> new Document("customerId", "CUS_race").append("openTerm", true)
                .append("grantSource", "INTERNAL_GRANT").append("grantRef", "race-" + java.util.UUID.randomUUID()));
    }

    // ---- 7. the one index DB-2 adds is the one the per-vertical scan actually uses -----------

    private static String planStages(Document plan) {
        StringBuilder sb = new StringBuilder();
        collect(plan, sb);
        return sb.toString();
    }

    private static void collect(Document p, StringBuilder sb) {
        if (p == null) return;
        sb.append(p.getString("stage"));
        if (p.containsKey("indexName")) sb.append('[').append(p.getString("indexName")).append(']');
        sb.append(' ');
        collect(p.get("queryPlan", Document.class), sb);
        collect(p.get("inputStage", Document.class), sb);
        List<?> in = p.getList("inputStages", Object.class);
        if (in != null) for (Object o : in) collect((Document) o, sb);
    }

    @Test
    void the_per_vertical_id_ordered_scan_is_served_by_the_dedicated_index_without_a_sort() {
        MongoCollection<Document> products = db.getCollection("products");
        List<WriteModel<Document>> batch = new ArrayList<>();
        Date now = new Date();
        int verticals = 30;
        for (int i = 0; i < 6000; i++) {
            batch.add(new InsertOneModel<>(new Document("_id", "TZP-IDX" + String.format("%06d", i))
                    .append("product_type", "single")
                    .append("identity", new Document("type", "internal").append("internal_key", "idx|" + i))
                    .append("brand_code", "BR").append("title", "t" + i).append("lifecycle", i % 5 == 0 ? "draft" : "active")
                    .append("classification", new Document("vertical_id", "TZV-IDX" + (i % verticals))
                            .append("release_id", "1.0.0").append("status", "confirmed")
                            .append("method_detail", new Document()).append("evidence_refs", List.of()))
                    .append("attributes", new Document()).append("attributes_meta", new Document("validated_release", "1.0.0"))
                    .append("version", 1).append("created_at", now)));
            if (batch.size() == 2000) { products.bulkWrite(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) products.bulkWrite(batch);
        try {
            // the stamp-worker shape: vertical equality, _id cursor, _id sort, _id-only projection
            // (the canonical-key backfill has no projection and is not asserted here; neither is a covered plan)
            Document explain = db.runCommand(new Document("explain", new Document("find", "products")
                    .append("filter", new Document("classification.vertical_id", "TZV-IDX7")
                            .append("_id", new Document("$gt", "TZP-IDX002000")))
                    .append("sort", new Document("_id", 1)).append("limit", 100)
                    .append("projection", new Document("_id", 1))).append("verbosity", "queryPlanner"));
            String stages = planStages(explain.get("queryPlanner", Document.class).get("winningPlan", Document.class));
            assertThat(stages).as("winning plan: " + stages)
                    .contains("IXSCAN[" + SchemaBootstrap.PRODUCT_VERTICAL_CURSOR_INDEX + "]")
                    .doesNotContain("SORT ").doesNotContain("COLLSCAN").doesNotContain("IXSCAN[_id_]");
        } finally {
            products.deleteMany(new Document("_id", new Document("$regex", "^TZP-IDX")));
        }
    }

    // ---- 8. the in-test manifest itself is well-formed ----------------------------------------
    // (This does NOT tie the markdown manifest to the test; the markdown is maintained by hand.)

    @Test
    void test_manifest_has_no_duplicate_entries() {
        Set<String> seen = new LinkedHashSet<>();
        for (Idx e : MANIFEST) {
            assertThat(seen.add(e.coll() + "." + e.effectiveName())).as("duplicate manifest entry " + e).isTrue();
        }
    }
}
