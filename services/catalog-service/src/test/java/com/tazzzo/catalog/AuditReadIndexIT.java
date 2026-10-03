package com.tazzzo.catalog;

import com.tazzzo.admin.audit.AuditEventQuery;
import com.tazzzo.admin.audit.AuditEventReader;
import com.tazzzo.admin.audit.AuditEventReader.LedgerQuery;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.ActorType;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Mongo evidence for the audit-read indexes: the EXACT per-ledger queries {@link AuditEventReader#plan} executes are
 * explained ({@code executionStats}) over a populated ledger. The default newest-first page, the request-id lookup, the
 * actor lookup and a cursor page must each be an IXSCAN of the named partial index, with no COLLSCAN and no blocking SORT,
 * examining only about as many documents as they return.
 */
class AuditReadIndexIT extends AbstractApiIT {

    static final List<String> LEDGERS = List.of("product_events", "node_events", "domain_events");
    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    static final int ROWS = 3000;

    @Autowired AuditEventReader reader;

    @BeforeAll
    void populate() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        for (String ledger : LEDGERS) {
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < ROWS; i++) {
                Document d = switch (ledger) {
                    case "product_events" -> new Document("type", i % 2 == 0 ? "CREATED" : "PRICE_UPDATED")
                            .append("product_id", "TZP-" + (i % 300));
                    case "node_events" -> new Document("event", "renamed").append("node_id", "TZV-" + (i % 50))
                            .append("release_id", "0.9.0");
                    default -> new Document("aggregate_type", "serviceability_pin")
                            .append("aggregate_id", "5600" + (i % 100)).append("type", "PIN_UPSERTED");
                };
                d.append("detail", new Document()).append("at", Date.from(T0.plusSeconds(i)));
                if (i % 10 == 0) {
                    docs.add(d); // unattributed historical row: outside the partial indexes
                } else {
                    docs.add(ActorDocuments.appendTo(d, new Actor(ActorType.HUMAN_ADMIN, "google:" + (i % 20),
                            "oidc:google:cms", String.format("req_%020x", i))));
                }
            }
            db.getCollection(ledger).insertMany(docs);
        }
    }

    static AuditEventQuery parse(String... kv) {
        Map<String, String[]> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new String[]{kv[i + 1]});
        }
        return AuditEventQuery.parse(m);
    }

    Document explain(LedgerQuery q) {
        BsonDocument filter = q.filter().toBsonDocument(Document.class, db.getCodecRegistry());
        BsonDocument sort = q.sort().toBsonDocument(Document.class, db.getCodecRegistry());
        BsonDocument projection = q.projection().toBsonDocument(Document.class, db.getCodecRegistry());
        return db.runCommand(new Document("explain", new Document("find", q.source().collection()).append("filter", filter)
                .append("sort", sort).append("projection", projection).append("limit", q.limit()))
                .append("verbosity", "executionStats"));
    }

    static void collect(Object node, List<String> stages, List<String> indexes) {
        if (node instanceof Document d) {
            if (d.get("stage") instanceof String s) {
                stages.add(s);
            }
            if (d.get("indexName") instanceof String n) {
                indexes.add(n);
            }
            d.values().forEach(v -> collect(v, stages, indexes));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collect(v, stages, indexes));
        }
    }

    /** Asserts the winning plan of every ledger query is an IXSCAN of {@code index}, without COLLSCAN or blocking SORT. */
    void assertPlan(AuditEventQuery query, String index, int maxExaminedPerLedger) {
        for (LedgerQuery q : reader.plan(query)) {
            Document ex = explain(q);
            Document winning = ex.get("queryPlanner", Document.class).get("winningPlan", Document.class);
            List<String> stages = new ArrayList<>();
            List<String> indexes = new ArrayList<>();
            collect(winning, stages, indexes);
            String where = q.source().collection() + " " + winning.toJson();
            assertThat(stages).as(where).contains("IXSCAN").doesNotContain("COLLSCAN", "SORT");
            assertThat(indexes).as(where).containsOnly(index);
            Document stats = ex.get("executionStats", Document.class);
            // Evidence line in the build log (no actor data: synthetic fixtures only).
            System.out.println("AUDIT-EXPLAIN " + q.source().collection() + " stages=" + stages + " index=" + indexes
                    + " nReturned=" + stats.get("nReturned") + " keysExamined=" + stats.get("totalKeysExamined")
                    + " docsExamined=" + stats.get("totalDocsExamined"));
            assertThat(stats.getInteger("totalDocsExamined")).as(where).isLessThanOrEqualTo(maxExaminedPerLedger);
            assertThat(stats.getInteger("totalKeysExamined")).as(where).isLessThanOrEqualTo(maxExaminedPerLedger + 1);
        }
    }

    @Test
    void the_named_partial_indexes_exist_on_every_ledger() {
        for (String ledger : LEDGERS) {
            Map<String, Document> byName = new HashMap<>();
            db.getCollection(ledger).listIndexes().forEach(i -> byName.put(i.getString("name"), i));
            Document attributed = new Document("actor", new Document("$type", "object"));
            assertThat(byName.get(SchemaBootstrap.AUDIT_IDX_RECENT).get("key", Document.class))
                    .isEqualTo(new Document("at", -1).append("_id", -1));
            assertThat(byName.get(SchemaBootstrap.AUDIT_IDX_ACTOR).get("key", Document.class))
                    .isEqualTo(new Document("actor.id", 1).append("at", -1).append("_id", -1));
            assertThat(byName.get(SchemaBootstrap.AUDIT_IDX_REQUEST).get("key", Document.class))
                    .isEqualTo(new Document("actor.request_id", 1).append("at", -1).append("_id", -1));
            for (String name : List.of(SchemaBootstrap.AUDIT_IDX_RECENT, SchemaBootstrap.AUDIT_IDX_ACTOR,
                    SchemaBootstrap.AUDIT_IDX_REQUEST)) {
                assertThat(byName.get(name).get("partialFilterExpression", Document.class)).as(name).isEqualTo(attributed);
                assertThat(byName.get(name).containsKey("expireAfterSeconds")).as("no TTL: " + name).isFalse();
            }
        }
    }

    @Test
    void bootstrap_is_idempotent_with_the_audit_indexes() {
        schemaBootstrap.bootstrap(db);
        schemaBootstrap.bootstrap(db);
        the_named_partial_indexes_exist_on_every_ledger();
    }

    @Test
    void the_default_newest_first_page_is_an_index_walk_of_audit_read_recent() {
        assertPlan(parse(), SchemaBootstrap.AUDIT_IDX_RECENT, 51);
        assertPlan(parse("limit", "100"), SchemaBootstrap.AUDIT_IDX_RECENT, 101);
    }

    @Test
    void the_request_id_lookup_is_a_point_lookup_on_audit_read_request() {
        assertPlan(parse("requestId", String.format("req_%020x", 1234)), SchemaBootstrap.AUDIT_IDX_REQUEST, 1);
        assertPlan(parse("requestId", String.format("req_%020x", 10)), SchemaBootstrap.AUDIT_IDX_REQUEST, 0);
    }

    @Test
    void the_actor_lookup_walks_audit_read_actor_in_page_order() {
        assertPlan(parse("actorId", "google:7"), SchemaBootstrap.AUDIT_IDX_ACTOR, 51);
    }

    @Test
    void a_cursor_page_stays_on_the_index() {
        var first = reader.read(parse("limit", "20"));
        assertThat(first.nextCursor()).isPresent();
        assertPlan(parse("limit", "20", "cursor", first.nextCursor().get()), SchemaBootstrap.AUDIT_IDX_RECENT, 25);
    }
}
