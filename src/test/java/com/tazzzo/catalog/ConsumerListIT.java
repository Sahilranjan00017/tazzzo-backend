package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerCursorCodec;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5C — LIST-1 / LIST-PAGE-1 / LIST-CURSOR-1 over real HTTP, real Mongo, real Redis.
 *
 * <p>Fixture: the seed as R1, then under Rice &amp; Grains (TZC-000001):
 * <pre>
 *   Basmati Rice        TZG-000001 > TZV-000001   TZP-L001..TZP-L025   25 eligible, policy v3
 *   Non-Basmati Rice    TZG-000002 > TZV-000004   TZP-M001..TZP-M003    3 eligible, policy v7
 *   plus, in TZV-000001: TZP-X-DRAFT · TZP-X-PROV · TZP-X-BUNDLE (excluded)
 *                        TZP-X-VP (variant_pack) · TZP-X-REL (other classification release) (included)
 * </pre>
 * So the vertical TZV-000001 lists 27, the category TZC-000001 lists 30, all in {@code _id ASC}.
 * Salt (TZC-000005) stays consumer-empty. Product finds, per-collection finds and the exact
 * products {@code find} commands (limit, projection) are observed at the driver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerListIT extends AbstractConsumerIT {

    /** FIXTURE key — not a production value. */
    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "list-cursor-fixture-key-32-bytes!".getBytes(StandardCharsets.UTF_8));

    private static final String STAPLES = "TZS-000001";
    private static final String C_RICE = "TZC-000001";
    private static final String C_SALT = "TZC-000005";
    private static final String G_BASMATI = "TZG-000001";
    private static final String V_BASMATI = "TZV-000001";
    private static final String V_BIRYANI = "TZV-000002";
    private static final String V_SONA = "TZV-000004";

    @Autowired MeterRegistry registry;
    @Autowired ConsumerCursorCodec codec;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_consumer_list_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> FIXTURE_KEY_B64);
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // FIXTURE VALUES ONLY — not production recommendations (Q5-c).
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @BeforeAll
    void seedAndStock() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_BASMATI)).first().getString("name"))
                .isEqualTo("Basmati Rice");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_SONA)).first().getString("parent_id"))
                .isEqualTo("TZG-000002");
        for (int i = 1; i <= 25; i++) {
            eligibleProduct(String.format("TZP-L%03d", i), V_BASMATI);
        }
        for (int i = 1; i <= 3; i++) {
            eligibleProduct(String.format("TZP-M%03d", i), V_SONA);
        }
        product("TZP-X-DRAFT", V_BASMATI, "draft", "confirmed", "single");
        product("TZP-X-PROV", V_BASMATI, "active", "provisional", "single");
        product("TZP-X-BUNDLE", null, "active", "confirmed", "bundle");
        product("TZP-X-VP", V_BASMATI, "active", "confirmed", "variant_pack");
        eligibleProduct("TZP-X-REL", V_BASMATI);
        db.getCollection("products").updateOne(eq("_id", "TZP-X-REL"),
                new Document("$set", new Document("classification.release_id", "SOME-OTHER-RELEASE")));
        // projection policies: one per stocked vertical, different versions (RP-6c)
        // `aged` is a boolean definition in the seed already (loaded above); only the policies are new.
        policy(V_BASMATI, "v3", "aged:1:Aged");
        policy(V_SONA, "v7", "aged:1:Aged");
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    // ---------- fixtures ----------

    private void policy(String vertical, String version, String... entries) {
        List<Document> attrs = new ArrayList<>();
        for (String e : entries) {
            String[] parts = e.split(":");
            attrs.add(new Document("attribute_key", parts[0])
                    .append("display_order", Integer.parseInt(parts[1]))
                    .append("display_label", parts[2]));
        }
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", vertical).append("projection_version", version)
                        .append("attributes", attrs));
    }

    private ResponseEntity<JsonNode> list(String nodeId, String query) {
        return get("/catalog/v1/categories/" + nodeId + "/products" + query, JsonNode.class);
    }

    private JsonNode ok(String nodeId, String query) {
        ResponseEntity<JsonNode> res = list(nodeId, query);
        assertThat(res.getStatusCode().value()).as(nodeId + query + " -> " + res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    private static List<String> ids(JsonNode body) {
        return body.get("items").findValuesAsText("id");
    }

    private static String cursorOf(JsonNode body) {
        return body.get("next_cursor").asText();
    }

    private static List<String> expectedIds(String prefix, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(String.format("TZP-%s%03d", prefix, i));
        }
        return out;
    }

    private double chargedSoFar() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "list").summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double requestsSoFar(String outcome) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", "list", "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private double requestsSoFarAllOutcomes() {
        double total = 0;
        for (var c : registry.find(ConsumerObservability.REQUESTS).tags("route", "list").counters()) {
            total += c.count();
        }
        return total;
    }

    private static void assertFlat(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
        assertThat(res.getBody().has("error")).as("flat ERR-1, never the nested CMS envelope").isFalse();
        assertThat(res.getBody().get("request_id").asText())
                .isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
        assertThat(res.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "request_id");
    }

    // ---------- first page, final page, exhausted cursor ----------

    @Test
    void the_first_page_is_twenty_by_default_in_id_order_with_a_continuation() {
        JsonNode body = ok(V_BASMATI, "");

        assertThat(ids(body)).containsExactlyElementsOf(expectedIds("L", 1, 20));
        assertThat(body.get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(body.has("next_cursor")).as("27 eligible: more than one page").isTrue();
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("resolved_release_id", "items", "next_cursor");
    }

    @Test
    void the_final_page_carries_the_remainder_and_no_cursor() {
        JsonNode first = ok(V_BASMATI, "");
        JsonNode last = ok(V_BASMATI, "?cursor=" + cursorOf(first));

        assertThat(ids(last)).as("L021..L025, then the variant pack and the other-release product")
                .containsExactly("TZP-L021", "TZP-L022", "TZP-L023", "TZP-L024", "TZP-L025",
                        "TZP-X-REL", "TZP-X-VP");
        assertThat(last.has("next_cursor")).as("omitted, not null").isFalse();
        assertThat(last.fieldNames()).toIterable().containsExactlyInAnyOrder("resolved_release_id", "items");
    }

    @Test
    void continuation_never_duplicates_and_covers_every_eligible_product_once() {
        Set<String> seen = new HashSet<>();
        List<String> inOrder = new ArrayList<>();
        JsonNode page = ok(C_RICE, "?page_size=7");
        int pages = 1;
        while (true) {
            for (String id : ids(page)) {
                assertThat(seen.add(id)).as("duplicate across pages: " + id).isTrue();
                inOrder.add(id);
            }
            if (!page.has("next_cursor")) break;
            page = ok(C_RICE, "?cursor=" + cursorOf(page));
            pages++;
        }
        List<String> expected = new ArrayList<>(expectedIds("L", 1, 25));
        expected.addAll(expectedIds("M", 1, 3));
        expected.add("TZP-X-REL");
        expected.add("TZP-X-VP");
        assertThat(inOrder).as("global _id ASC across the whole scope, across pages").isEqualTo(expected);
        assertThat(pages).isEqualTo(5);   // 7+7+7+7+2
    }

    @Test
    void an_exactly_full_final_page_has_no_continuation_because_the_lookahead_row_did_not_exist() {
        // 30 eligible under Rice & Grains, page_size 10: page 3 holds exactly 10. The +1 read
        // (limit 11) returns 10 rows, so there is no continuation -- and a ">=" slip in the
        // sentinel check would mint a cursor to an empty fourth page.
        JsonNode p1 = ok(C_RICE, "?page_size=10");
        JsonNode p2 = ok(C_RICE, "?cursor=" + cursorOf(p1));
        resetCounters();
        JsonNode p3 = ok(C_RICE, "?cursor=" + cursorOf(p2));

        assertThat(ids(p1)).containsExactlyElementsOf(expectedIds("L", 1, 10));
        assertThat(ids(p2)).containsExactlyElementsOf(expectedIds("L", 11, 20));
        assertThat(ids(p3)).hasSize(10).startsWith("TZP-L021").endsWith("TZP-X-VP");
        assertThat(PRODUCT_FIND_COMMANDS.get(1).getInteger("limit")).isEqualTo(11);
        assertThat(p3.has("next_cursor")).as("exactly-full last page: no +1 row, no cursor").isFalse();
    }

    @Test
    void a_visible_scope_with_an_exhausted_valid_cursor_is_200_empty_not_404() {
        // A real signed cursor positioned after the last eligible product of the scope.
        String exhausted = codec.encode(new ConsumerCursorCodec.ListCursor(V_BASMATI, "R1", 20, "TZP-X-VP"));
        JsonNode body = ok(V_BASMATI, "?cursor=" + exhausted);

        assertThat(body.get("items")).isEmpty();
        assertThat(body.has("next_cursor")).isFalse();
        assertThat(body.get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(PRODUCT_FINDS.get()).as("the scope probe (hit) and the page read (empty)").isEqualTo(2);
    }

    @Test
    void products_deleted_between_pages_simply_disappear_and_new_higher_ids_appear() {
        JsonNode first = ok(V_BASMATI, "?page_size=20");
        String cursor = cursorOf(first);
        db.getCollection("products").deleteOne(eq("_id", "TZP-L023"));
        eligibleProduct("TZP-L000", V_BASMATI);      // lower than the cursor: never seen on page 2
        eligibleProduct("TZP-X-ZZZ", V_BASMATI);     // higher: appears
        try {
            assertThat(ids(ok(V_BASMATI, "?cursor=" + cursor)))
                    .as("keyset-stable, not snapshot-stable (PAG-2 §2)")
                    .containsExactly("TZP-L021", "TZP-L022", "TZP-L024", "TZP-L025",
                            "TZP-X-REL", "TZP-X-VP", "TZP-X-ZZZ");
        } finally {
            db.getCollection("products").deleteMany(new Document("_id",
                    new Document("$in", List.of("TZP-L000", "TZP-X-ZZZ"))));
            eligibleProduct("TZP-L023", V_BASMATI);
        }
    }

    // ---------- LIST-PAGE-1 ----------

    @Test
    void page_size_bounds_are_enforced_and_never_clamped() {
        assertThat(ids(ok(V_BASMATI, "?page_size=1"))).containsExactly("TZP-L001");
        assertThat(ids(ok(C_RICE, "?page_size=50"))).hasSize(30);

        for (String bad : new String[]{"0", "51", "-1", "abc", "20.5", "", "1,2", "9999999999"}) {
            ResponseEntity<JsonNode> res = list(V_BASMATI, "?page_size=" + bad);
            assertFlat(res, 400, "INVALID_REQUEST");
        }
        assertThat(list(V_BASMATI, "?page_size=51").getBody().has("items"))
                .as("51 is refused, not served as 50").isFalse();
    }

    @Test
    void a_rejected_page_size_costs_nothing_and_reads_nothing() {
        double before = chargedSoFar();
        assertFlat(list(V_BASMATI, "?page_size=0"), 400, "INVALID_REQUEST");
        assertThat(chargedSoFar() - before).isZero();
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    // ---------- N+1 and the projected fields ----------

    @Test
    void the_page_read_fetches_at_most_page_size_plus_one_and_only_the_five_fields() {
        ok(V_BASMATI, "?page_size=5");
        // command 0 is the scope probe (limit 1, _id only); command 1 is the page read
        assertThat(PRODUCT_FIND_COMMANDS).hasSize(2);
        Document probe = PRODUCT_FIND_COMMANDS.get(0);
        Document page = PRODUCT_FIND_COMMANDS.get(1);
        assertThat(probe.getInteger("limit")).isEqualTo(1);
        assertThat(page.getInteger("limit")).as("N+1 lookahead, no count query").isEqualTo(6);
        assertThat(page.getList("projection", String.class))
                .containsExactlyInAnyOrder("_id", "title", "brand_code", "classification.vertical_id", "attributes");
        assertThat(page.getBoolean("has_skip")).isFalse();
        assertThat(probe.getBoolean("has_skip")).isFalse();

        resetCounters();
        ok(V_BASMATI, "");
        assertThat(PRODUCT_FIND_COMMANDS.get(1).getInteger("limit")).isEqualTo(21);
    }

    @Test
    void the_lookahead_row_is_continuation_evidence_only_and_is_never_projected() {
        // TZP-L020A sorts between L020 and L021, in a vertical whose policy is MALFORMED. If the
        // 21st row were projected, page 1 of 20 would fail with 503. It must succeed -- and page 2,
        // which genuinely needs that policy, must fail closed as a whole.
        eligibleProduct("TZP-L020A", V_BIRYANI);
        db.getCollection("consumer_projection_policy").insertOne(new Document("vertical_id", V_BIRYANI)
                .append("projection_version", "v9")
                .append("attributes", List.of(new Document("attribute_key", "aged").append("display_label", "Aged"))));
        try {
            JsonNode first = ok(G_BASMATI, "?page_size=20");
            assertThat(ids(first)).containsExactlyElementsOf(expectedIds("L", 1, 20));
            assertThat(first.has("next_cursor")).isTrue();
            assertThat(PRODUCT_FIND_COMMANDS.get(1).getInteger("limit")).isEqualTo(21);

            ResponseEntity<JsonNode> second = list(G_BASMATI, "?cursor=" + cursorOf(first));
            assertFlat(second, 503, "SERVICE_UNAVAILABLE");
            assertThat(second.getHeaders().getFirst("Retry-After")).isNull();
        } finally {
            db.getCollection("consumer_projection_policy").deleteOne(eq("vertical_id", V_BIRYANI));
            db.getCollection("products").deleteOne(eq("_id", "TZP-L020A"));
        }
    }

    // ---------- scope levels ----------

    @Test
    void every_level_of_the_hierarchy_is_a_valid_scope() {
        List<String> vertical = ids(ok(V_BASMATI, "?page_size=50"));
        List<String> subCategory = ids(ok(G_BASMATI, "?page_size=50"));
        List<String> category = ids(ok(C_RICE, "?page_size=50"));
        List<String> superCategory = ids(ok(STAPLES, "?page_size=50"));

        assertThat(vertical).hasSize(27);
        assertThat(subCategory).as("the only stocked vertical under Basmati Rice").isEqualTo(vertical);
        assertThat(category).hasSize(30).containsAll(vertical).containsAll(expectedIds("M", 1, 3));
        assertThat(superCategory).as("nothing else under Staples is stocked").isEqualTo(category);
    }

    // ---------- hidden / missing / consumer-empty ----------

    @Test
    void a_consumer_empty_active_node_is_a_flat_404_after_one_probe_with_the_full_charge() {
        double before = chargedSoFar();
        ResponseEntity<JsonNode> res = list(C_SALT, "");

        assertFlat(res, 404, "NOT_FOUND");
        assertThat(PRODUCT_FINDS.get()).as("the scope probe only; no page read").isEqualTo(1);
        assertThat(chargedSoFar() - before).as("1 + 20, charged BEFORE the probe").isEqualTo(21);
    }

    @Test
    void a_missing_node_is_a_flat_404_that_costs_one_and_probes_nothing() {
        double before = chargedSoFar();
        assertFlat(list("TZC-999999", ""), 404, "NOT_FOUND");
        assertThat(PRODUCT_FINDS.get()).isZero();
        assertThat(chargedSoFar() - before).isEqualTo(1);
    }

    @Test
    void a_holding_vertical_is_never_a_scope_even_when_stocked() {
        eligibleProduct("TZP-HOLD", "TZV-UNCLASSIFIED");
        try {
            assertFlat(list("TZV-UNCLASSIFIED", ""), 404, "NOT_FOUND");
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-HOLD"));
        }
    }

    @Test
    void an_unknown_explicit_release_is_404_before_any_charge() {
        double before = chargedSoFar();
        assertFlat(list(V_BASMATI, "?release=NOPE"), 404, "NOT_FOUND");
        assertThat(chargedSoFar() - before).isZero();
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    // ---------- eligibility is the shared predicate ----------

    @Test
    void only_currently_eligible_singles_and_variant_packs_are_listed() {
        List<String> all = ids(ok(V_BASMATI, "?page_size=50"));

        // The bundle is outside every scope BY SCHEMA (a bundle's vertical_id is null, so no
        // within(scope) can match it); the product_type axis itself is proved in
        // ConsumerEligibilityIT and guarded against duplication by ConsumerPredicateGuardIT.
        assertThat(all).doesNotContain("TZP-X-DRAFT", "TZP-X-PROV", "TZP-X-BUNDLE");
        assertThat(all).contains("TZP-X-VP");
        assertThat(all).as("R-A: classification.release_id is provenance, not membership").contains("TZP-X-REL");
    }

    // ---------- the cursor contract ----------

    /**
     * That the cursor's release WINS over a moved pointer is proved in ConsumerListTopologyIT
     * (Order 3). Here: continuation never even reads the pointer.
     */
    @Test
    void a_continuation_does_not_read_the_current_pointer_and_accepts_a_matching_release() {
        String cursor = cursorOf(ok(V_BASMATI, ""));
        resetCounters();
        assertThat(ok(V_BASMATI, "?cursor=" + cursor).get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(finds("system_config")).as("'current' is not re-resolved on continuation").isZero();
        assertThat(finds("catalogue_releases")).as("the cursor's release is verified as a real active release").isEqualTo(1);
        assertThat(ok(V_BASMATI, "?cursor=" + cursor + "&release=R1").get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(ids(ok(V_BASMATI, "?cursor=" + cursor + "&page_size=20"))).hasSize(7);
    }

    @Test
    void a_cursor_reused_with_a_different_release_page_size_or_node_is_INVALID_CURSOR() {
        String cursor = cursorOf(ok(V_BASMATI, "?page_size=10"));

        assertFlat(list(V_BASMATI, "?cursor=" + cursor + "&release=R2"), 400, "INVALID_CURSOR");
        assertFlat(list(V_BASMATI, "?cursor=" + cursor + "&page_size=11"), 400, "INVALID_CURSOR");
        assertFlat(list(V_BASMATI, "?cursor=" + cursor + "&page_size=0"), 400, "INVALID_CURSOR");
        assertFlat(list(G_BASMATI, "?cursor=" + cursor), 400, "INVALID_CURSOR");
        assertFlat(list(C_RICE, "?cursor=" + cursor), 400, "INVALID_CURSOR");
        // a page_size that is not a number at all is a malformed REQUEST, cursor or not
        assertFlat(list(V_BASMATI, "?cursor=" + cursor + "&page_size=ten"), 400, "INVALID_REQUEST");
    }

    @Test
    void tampered_malformed_oversized_and_over_max_cursors_are_INVALID_CURSOR_without_any_work() {
        String cursor = cursorOf(ok(V_BASMATI, ""));
        byte[] raw = Base64.getUrlDecoder().decode(cursor);
        raw[raw.length - 40] ^= 0x01;                                    // inside the payload
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        raw = Base64.getUrlDecoder().decode(cursor);
        raw[raw.length - 1] ^= 0x01;                                     // inside the signature
        String tamperedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String overMax = codec.encode(new ConsumerCursorCodec.ListCursor(V_BASMATI, "R1", 51, "TZP-L001"));
        String tooLong = "A".repeat(2049);

        resetCounters();
        double before = chargedSoFar();
        for (String bad : new String[]{tamperedPayload, tamperedSignature, "not-a-cursor", "AAAA", tooLong, overMax}) {
            assertFlat(list(V_BASMATI, "?cursor=" + bad), 400, "INVALID_CURSOR");
        }
        assertThat(PRODUCT_FINDS.get()).as("rejected before any product work").isZero();
        assertThat(chargedSoFar() - before).as("and before any charge").isZero();
        assertFlat(list(V_BASMATI, "?cursor="), 400, "INVALID_CURSOR");   // empty is a bad cursor, not a first page
    }

    @Test
    void a_cursor_from_another_node_cannot_be_replayed_even_when_the_position_exists_there() {
        // A Rice & Grains cursor positioned at L010 would be a perfectly valid position inside the
        // Basmati vertical too. Binding the node refuses it anyway (L-7).
        String cursor = cursorOf(ok(C_RICE, "?page_size=10"));
        assertFlat(list(V_BASMATI, "?cursor=" + cursor), 400, "INVALID_CURSOR");
    }

    // ---------- projection: once per page, item-local ----------

    @Test
    void the_page_is_projected_once_with_item_local_versions_and_bounded_reads() {
        JsonNode body = ok(C_RICE, "?page_size=50");

        for (JsonNode item : body.get("items")) {
            String expected = item.get("id").asText().startsWith("TZP-M") ? "v7" : "v3";
            assertThat(item.get("projectionVersion").asText()).as(item.get("id").asText()).isEqualTo(expected);
            assertThat(item.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("id", "title", "brandCode", "attributes", "projectionVersion");
        }
        assertThat(finds("consumer_projection_policy")).as("one policy read for a 30-item page").isEqualTo(1);
        assertThat(finds("attribute_definitions")).as("one definition read for a 30-item page").isEqualTo(1);
        assertThat(finds("products")).as("probe + page").isEqualTo(2);
    }

    @Test
    void a_malformed_policy_the_page_needs_is_a_flat_503_with_no_partial_page() {
        db.getCollection("consumer_projection_policy").deleteOne(eq("vertical_id", V_SONA));
        db.getCollection("consumer_projection_policy").insertOne(new Document("vertical_id", V_SONA)
                .append("projection_version", "  ")
                .append("attributes", List.of()));
        try {
            ResponseEntity<JsonNode> res = list(C_RICE, "?page_size=50");
            assertFlat(res, 503, "SERVICE_UNAVAILABLE");
            assertThat(res.getBody().has("items")).isFalse();
            assertThat(ids(ok(V_BASMATI, "?page_size=5"))).as("a page not needing that vertical is unaffected").hasSize(5);
        } finally {
            db.getCollection("consumer_projection_policy").deleteOne(eq("vertical_id", V_SONA));
            policy(V_SONA, "v7", "aged:1:Aged");
        }
    }

    // ---------- response shape ----------

    @Test
    void the_response_carries_nothing_but_the_ratified_fields() {
        JsonNode body = ok(V_BASMATI, "?page_size=3");
        for (String forbidden : new String[]{"total", "total_count", "total_pages", "page", "page_number",
                "projection_version", "projectionVersion", "taxonomyPath", "taxonomy_path", "price",
                "inventory", "seller", "ranking", "count"}) {
            assertThat(body.has(forbidden)).as("envelope." + forbidden).isFalse();
        }
        JsonNode item = body.get("items").get(0);
        for (String forbidden : new String[]{"lifecycle", "classification", "version", "canonical_key",
                "attribute_schema_id", "identity", "attributes_meta", "created_at", "product_type",
                "offers_current", "price", "taxonomyPath", "status", "release_id", "_id"}) {
            assertThat(item.has(forbidden)).as("item." + forbidden).isFalse();
        }
        assertThat(item.get("id").asText()).isEqualTo("TZP-L001");
        assertThat(item.get("title").asText()).isEqualTo("T TZP-L001");
        assertThat(item.get("brandCode").asText()).isEqualTo("BR");
        assertThat(item.get("attributes").isArray()).isTrue();
    }

    // ---------- Q4-a ----------

    @Test
    void the_bearer_header_is_irrelevant_to_the_answer() {
        String anonymous = ok(V_BASMATI, "?page_size=3").toString();
        for (String token : new String[]{"definitely-not-a-token", "cms-test-token"}) {
            HttpHeaders h = bearer(token);
            ResponseEntity<JsonNode> res = get("/catalog/v1/categories/" + V_BASMATI + "/products?page_size=3", h, JsonNode.class);
            assertThat(res.getBody().toString()).isEqualTo(anonymous);
        }
    }

    // ---------- CONSUMER-OBS-2: the framework's HTTP vocabulary does not exist here ----------

    @Test
    void the_framework_http_server_requests_observation_is_absent_and_ours_is_present() {
        // Proof is the REGISTRY after real traffic on all three routes, not the property.
        assertThat(get("/catalog/v1/categories", JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/catalog/v1/categories/" + STAPLES + "/children", JsonNode.class).getStatusCode().value()).isEqualTo(200);
        ok(V_BASMATI, "?page_size=3");
        assertThat(list("TZC-999999", "").getStatusCode().value()).isEqualTo(404);   // an error path too

        assertThat(registry.find("http.server.requests").meters())
                .as("Spring's automatic http.server.requests (uri template + exception tags) must not exist")
                .isEmpty();
        assertThat(registry.find("http.server.requests.active").meters()).isEmpty();
        List<String> frameworkHttpMeters = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            if (meter.getId().getName().startsWith("http.server.")) {
                frameworkHttpMeters.add(meter.getId().getName());
            }
        }
        assertThat(frameworkHttpMeters).isEmpty();
        assertThat(registry.find(ConsumerObservability.REQUEST_DURATION).tags("route", "list").timers())
                .as("our bounded route vocabulary is the HTTP measurement contract").isNotEmpty();
        assertThat(registry.find(ConsumerObservability.REQUEST_DURATION).tags("route", "root").timers()).isNotEmpty();
        assertThat(registry.find(ConsumerObservability.REQUEST_DURATION).tags("route", "children").timers()).isNotEmpty();
    }

    // ---------- Q5-OBS-1b ----------

    @Test
    void every_list_request_records_exactly_one_outcome_and_only_bounded_tag_values() {
        double all = requestsSoFarAllOutcomes();
        double success = requestsSoFar("success");
        double notFound = requestsSoFar("not_found");
        double invalidRequest = requestsSoFar("invalid_request");
        double invalidCursor = requestsSoFar("invalid_cursor");

        String cursor = cursorOf(ok(V_BASMATI, ""));          // success
        ok(V_BASMATI, "?cursor=" + cursor);                  // success
        list("TZC-999999", "");                              // not_found
        list(C_SALT, "");                                    // not_found
        list(V_BASMATI, "?page_size=0");                     // invalid_request
        list(V_BASMATI, "?cursor=garbage");                  // invalid_cursor
        list(G_BASMATI, "?cursor=" + cursor);                // invalid_cursor (node)

        assertThat(requestsSoFarAllOutcomes() - all).as("seven requests, seven observations").isEqualTo(7);
        assertThat(requestsSoFar("success") - success).isEqualTo(2);
        assertThat(requestsSoFar("not_found") - notFound).isEqualTo(2);
        assertThat(requestsSoFar("invalid_request") - invalidRequest).isEqualTo(1);
        assertThat(requestsSoFar("invalid_cursor") - invalidCursor).isEqualTo(2);
        assertThat(registry.find(ConsumerObservability.PROBE_DURATION)
                .tags("route", "list", "scope", "parent").timers()).isNotEmpty();
        assertThat(registry.find(ConsumerObservability.PROBE_DURATION)
                .tags("route", "list", "scope", "child").timers()).as("LIST has no child probes").isEmpty();

        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("tazzzo.catalog.consumer.")) continue;
            for (Tag tag : meter.getId().getTags()) {
                if (!ConsumerObservability.ALLOWED_TAG_KEYS.contains(tag.getKey())) {
                    violations.add(meter.getId().getName() + " key " + tag.getKey());
                }
                // Every ratified tag value is a lower-case enum word. A cursor, a node id, a
                // product id, a release id, a page size, an IP or a path is none of those.
                if (!tag.getValue().matches("[a-z_]{1,20}")) {
                    violations.add(meter.getId().getName() + " " + tag.getKey() + "=" + tag.getValue());
                }
            }
        }
        assertThat(violations).as("cardinality is a contract, not a convention").isEmpty();
    }
}
