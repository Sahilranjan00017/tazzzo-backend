package com.tazzzo.catalog;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.tazzzo.catalog.consumer.ConsumerAttributeResponse;
import com.tazzzo.catalog.consumer.ConsumerProductResponse;
import com.tazzzo.catalog.consumer.ConsumerProjectionPolicyException;
import com.tazzzo.catalog.consumer.ConsumerProjectionService;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5C.0 — PHASE-5-BATCH-1. The projector's read plan is CONSTANT per page: at most one
 * {@code find} on {@code consumer_projection_policy} and at most one on
 * {@code attribute_definitions}, however many products or projected attributes the page carries.
 *
 * <p>The counts are facts, not intentions: the service under test is constructed over a Mongo
 * client whose command listener counts every {@code find} by collection. Nothing is mocked; the
 * Mongo is the same container the rest of the suite uses.
 */
class ConsumerProjectionBatchIT extends AbstractMongoIT {

    private static final String REL = "rel-1.0";
    private static final String V_A = "TZV-000001";
    private static final String V_B = "TZV-000010";
    private static final String V_C = "TZV-000020";   // never gets a policy

    private static final Map<String, AtomicInteger> FINDS = new ConcurrentHashMap<>();

    private MongoClient counting;
    private ConsumerProjectionService projection;

    @BeforeAll
    void serviceOverACountingClient() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO.getReplicaSetUrl()))
                .addCommandListener(new CommandListener() {
                    @Override
                    public void commandStarted(CommandStartedEvent event) {
                        if ("find".equals(event.getCommandName()) && event.getCommand().containsKey("find")) {
                            FINDS.computeIfAbsent(event.getCommand().getString("find").getValue(),
                                    k -> new AtomicInteger()).incrementAndGet();
                        }
                    }
                })
                .build();
        counting = MongoClients.create(settings);
        projection = new ConsumerProjectionService(counting.getDatabase(db.getName()));
    }

    @AfterAll
    void closeCountingClient() {
        counting.close();
    }

    @BeforeEach
    void reset() {
        db.getCollection("products").deleteMany(new Document());
        db.getCollection("consumer_projection_policy").deleteMany(new Document());
        db.getCollection("attribute_definitions").deleteMany(new Document());
        definition("grain_length", "enum_open", "descriptive", List.of("long", "medium", "short"), null);
        definition("aged", "boolean", "descriptive", null, null);
        definition("brand", "string", "descriptive", null, null);
        definition("pack_size", "number", "descriptive", null, "pack_unit");
        definition("pack_unit", "enum_open", "descriptive", List.of("kg", "g", "pieces"), null);
        definition("organic_certified", "boolean", "claim", null, null);
        definition("form", "enum_open", "descriptive", List.of("whole", "split"), null);
        FINDS.clear();
    }

    // ---------- fixtures (same shapes as ConsumerProjectionIT) ----------

    private void definition(String key, String type, String governance,
                            List<String> knownValues, String pairedUnit) {
        Document d = new Document("key", key).append("version", 1).append("type", type)
                .append("governance", governance).append("status", "active");
        if (knownValues != null) d.append("known_values", knownValues);
        if (pairedUnit != null) d.append("paired_unit", pairedUnit);
        db.getCollection("attribute_definitions").insertOne(d);
    }

    /**
     * A validator-conformant product. A null vertical makes it a BUNDLE, because the products
     * validator permits {@code classification.vertical_id == null} for bundles only — the one
     * real shape in which a product reaches the projector without a vertical.
     */
    private Document product(String id, String vertical, Map<String, Object> attributes) {
        Document p = new Document("_id", id).append("product_type", vertical == null ? "bundle" : "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "Title " + id)
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", vertical)
                        .append("release_id", REL).append("status", "confirmed"))
                .append("attributes", new Document(attributes))
                .append("attributes_meta", new Document("validated_release", REL))
                .append("version", 1).append("created_at", new Date());
        if (vertical == null) {
            p.append("bundle_contents", List.of(
                    new Document("component_product_id", "TZP-C1").append("qty", 1),
                    new Document("component_product_id", "TZP-C2").append("qty", 1)));
        }
        db.getCollection("products").insertOne(p);
        return db.getCollection("products").find(new Document("_id", id)).first();
    }

    /** Each entry is key:order:label. */
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

    private static final String[] SIX_KEYS = {"grain_length:1:Grain", "aged:2:Aged", "brand:3:Brand",
            "pack_size:4:Pack", "organic_certified:5:Organic", "form:6:Form"};

    private static final Map<String, Object> FULL_ATTRIBUTES = Map.of(
            "grain_length", "long", "aged", true, "brand", "X", "pack_size", 5, "pack_unit", "kg",
            "organic_certified", true, "form", "whole");

    private int finds(String collection) {
        AtomicInteger n = FINDS.get(collection);
        return n == null ? 0 : n.get();
    }

    private static List<String> keys(ConsumerProductResponse r) {
        return r.attributes().stream().map(ConsumerAttributeResponse::key).toList();
    }

    private static List<String> ids(List<ConsumerProductResponse> page) {
        return page.stream().map(ConsumerProductResponse::id).toList();
    }

    // ---------- the read gate ----------

    @Test
    void empty_input_is_empty_output_with_no_read_of_any_kind() {
        policy(V_A, "v3", SIX_KEYS);
        FINDS.clear();

        assertThat(projection.projectAll(List.of())).isEmpty();
        assertThat(finds("consumer_projection_policy")).isZero();
        assertThat(finds("attribute_definitions")).isZero();
    }

    @Test
    void one_product_costs_one_policy_read_and_one_definition_read() {
        policy(V_A, "v3", SIX_KEYS);
        Document p = product("TZP-1", V_A, FULL_ATTRIBUTES);
        FINDS.clear();

        List<ConsumerProductResponse> page = projection.projectAll(List.of(p));

        assertThat(page).hasSize(1);
        assertThat(keys(page.get(0)))
                .as("claim and form suppressed; the other four publish, in display order")
                .containsExactly("grain_length", "aged", "brand", "pack_size");
        assertThat(finds("consumer_projection_policy")).isEqualTo(1);
        assertThat(finds("attribute_definitions")).isEqualTo(1);
    }

    @Test
    void thirty_products_across_three_verticals_cost_exactly_the_same_two_reads() {
        policy(V_A, "v3", SIX_KEYS);
        policy(V_B, "v7", SIX_KEYS);
        List<Document> page = new ArrayList<>();
        String[] verticals = {V_A, V_B, V_C};
        for (int i = 0; i < 30; i++) {
            page.add(product("TZP-" + i, verticals[i % 3], FULL_ATTRIBUTES));
        }
        FINDS.clear();

        List<ConsumerProductResponse> out = projection.projectAll(page);

        assertThat(out).hasSize(30);
        assertThat(finds("consumer_projection_policy"))
                .as("30 products, 3 verticals, 6 opted-in keys: still ONE policy read")
                .isEqualTo(1);
        assertThat(finds("attribute_definitions"))
                .as("and ONE definition read — not one per product, not one per attribute")
                .isEqualTo(1);
        for (int i = 0; i < 30; i++) {
            ConsumerProductResponse r = out.get(i);
            assertThat(r.id()).isEqualTo("TZP-" + i);
            if (i % 3 == 2) {
                assertThat(keys(r)).isEmpty();
                assertThat(r.projectionVersion()).isNull();
            } else {
                assertThat(keys(r)).containsExactly("grain_length", "aged", "brand", "pack_size");
                assertThat(r.projectionVersion()).isEqualTo(i % 3 == 0 ? "v3" : "v7");
            }
        }
    }

    @Test
    void the_read_count_does_not_grow_with_the_number_of_projected_attributes() {
        policy(V_A, "v1", "aged:1:Aged");
        Document one = product("TZP-ONE", V_A, FULL_ATTRIBUTES);
        FINDS.clear();
        projection.projectAll(List.of(one));
        int policyReadsForOneKey = finds("consumer_projection_policy");
        int definitionReadsForOneKey = finds("attribute_definitions");

        db.getCollection("consumer_projection_policy").deleteMany(new Document());
        policy(V_A, "v6", SIX_KEYS);
        FINDS.clear();
        projection.projectAll(List.of(one, one, one, one, one));

        assertThat(finds("consumer_projection_policy")).isEqualTo(policyReadsForOneKey).isEqualTo(1);
        assertThat(finds("attribute_definitions"))
                .as("six keys, one with a paired unit, five products: the plan is unchanged")
                .isEqualTo(definitionReadsForOneKey).isEqualTo(1);
    }

    @Test
    void a_page_whose_products_carry_no_vertical_reads_nothing_and_renders_no_policy() {
        policy(V_A, "v3", SIX_KEYS);
        Document orphan = product("TZP-ORPHAN", null, FULL_ATTRIBUTES);
        FINDS.clear();

        List<ConsumerProductResponse> out = projection.projectAll(List.of(orphan, orphan));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).attributes()).isEmpty();
        assertThat(out.get(0).projectionVersion()).isNull();
        assertThat(finds("consumer_projection_policy")).as("no vertical, nothing to look up").isZero();
        assertThat(finds("attribute_definitions")).isZero();
    }

    @Test
    void a_page_under_authored_empty_policies_reads_no_definitions() {
        policy(V_A, "v9");
        Document p = product("TZP-E", V_A, FULL_ATTRIBUTES);
        FINDS.clear();

        List<ConsumerProductResponse> out = projection.projectAll(List.of(p));

        assertThat(out.get(0).attributes()).isEmpty();
        assertThat(out.get(0).projectionVersion())
                .as("authored-and-empty is a REAL policy with a real version, unlike unauthored")
                .isEqualTo("v9");
        assertThat(finds("consumer_projection_policy")).isEqualTo(1);
        assertThat(finds("attribute_definitions")).as("nothing opted in, nothing to verify").isZero();
    }

    // ---------- order, cardinality, item-local version ----------

    @Test
    void a_mixed_vertical_page_preserves_input_order_and_item_local_versions() {
        policy(V_A, "v3", "aged:1:Aged");
        policy(V_B, "v7", "aged:1:Aged");
        Document a = product("TZP-A", V_A, Map.of("aged", true));
        Document b = product("TZP-B", V_B, Map.of("aged", true));
        Document c = product("TZP-C", V_C, Map.of("aged", true));
        Document n = product("TZP-N", null, Map.of("aged", true));

        List<ConsumerProductResponse> out = projection.projectAll(List.of(c, a, b, n, a));

        assertThat(ids(out)).containsExactly("TZP-C", "TZP-A", "TZP-B", "TZP-N", "TZP-A");
        assertThat(out.stream().map(ConsumerProductResponse::projectionVersion).toList())
                .as("each item carries ITS vertical's version; there is no page version")
                .containsExactly(null, "v3", "v7", null, "v3");
        assertThat(keys(out.get(0))).isEmpty();
        assertThat(keys(out.get(1))).containsExactly("aged");
        assertThat(keys(out.get(2))).containsExactly("aged");
        assertThat(keys(out.get(3))).isEmpty();
    }

    @Test
    void duplicate_product_entries_keep_order_and_cardinality() {
        policy(V_A, "v3", "aged:1:Aged");
        Document a = product("TZP-A", V_A, Map.of("aged", true));
        Document b = product("TZP-B", V_A, Map.of("aged", false));

        List<ConsumerProductResponse> out = projection.projectAll(List.of(a, a, b, a));

        assertThat(ids(out)).containsExactly("TZP-A", "TZP-A", "TZP-B", "TZP-A");
        assertThat(out.get(0)).isEqualTo(out.get(1)).isEqualTo(out.get(3));
        assertThat(out.get(2).attributes().get(0).value()).isEqualTo(false);
    }

    @Test
    void single_project_equals_projectAll_of_one_and_of_the_page() {
        policy(V_A, "v3", SIX_KEYS);
        policy(V_B, "v7", "pack_size:1:Pack", "grain_length:2:Grain");
        List<Document> page = List.of(
                product("TZP-1", V_A, FULL_ATTRIBUTES),
                product("TZP-2", V_B, Map.of("pack_size", 2, "pack_unit", "furlongs", "grain_length", "short")),
                product("TZP-3", V_C, FULL_ATTRIBUTES),
                product("TZP-4", V_A, Map.of("grain_length", "supplier-text", "aged", "yes")),
                product("TZP-5", null, FULL_ATTRIBUTES));

        List<ConsumerProductResponse> batched = projection.projectAll(page);

        for (int i = 0; i < page.size(); i++) {
            ConsumerProductResponse single = projection.project(page.get(i));
            assertThat(batched.get(i))
                    .as("item %d: the page context must not change what one product renders as", i)
                    .isEqualTo(single)
                    .isEqualTo(projection.projectAll(List.of(page.get(i))).get(0));
        }
        assertThat(keys(batched.get(1))).as("bad unit suppresses the pair; grain publishes").containsExactly("grain_length");
        assertThat(keys(batched.get(3))).as("unknown enum_open and a non-boolean 'aged' both suppress").isEmpty();
    }

    // ---------- RP-6d: a malformed policy fails the WHOLE page ----------

    @Test
    void a_malformed_policy_for_any_vertical_on_the_page_fails_the_whole_page() {
        policy(V_A, "v3", "aged:1:Aged");
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", V_B).append("projection_version", "v7")
                        .append("attributes", List.of(new Document("attribute_key", "aged")
                                .append("display_label", "Aged"))));    // no display_order
        Document a = product("TZP-A", V_A, Map.of("aged", true));
        Document b = product("TZP-B", V_B, Map.of("aged", true));

        assertThatThrownBy(() -> projection.projectAll(List.of(a, b)))
                .as("no partial page, no silent repair: the configuration is broken")
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("display_order");
        assertThatThrownBy(() -> projection.projectAll(List.of(b, a)))
                .isInstanceOf(ConsumerProjectionPolicyException.class);

        assertThat(projection.projectAll(List.of(a)).get(0).projectionVersion())
                .as("a page that does not need the broken vertical is unaffected")
                .isEqualTo("v3");
    }

    // ---------- the RP rules, through the batch path ----------

    @Test
    void claim_and_form_are_suppressed_even_when_every_policy_lists_them() {
        policy(V_A, "v3", "organic_certified:1:Organic", "form:2:Form", "aged:3:Aged");
        policy(V_B, "v7", "form:1:Form", "organic_certified:2:Organic", "brand:3:Brand");
        List<ConsumerProductResponse> out = projection.projectAll(List.of(
                product("TZP-A", V_A, FULL_ATTRIBUTES), product("TZP-B", V_B, FULL_ATTRIBUTES)));

        assertThat(keys(out.get(0))).containsExactly("aged");
        assertThat(keys(out.get(1))).containsExactly("brand");
    }

    @Test
    void enum_open_publishes_only_the_ratified_vocabulary() {
        policy(V_A, "v3", "grain_length:1:Grain");
        List<ConsumerProductResponse> out = projection.projectAll(List.of(
                product("TZP-K", V_A, Map.of("grain_length", "medium")),
                product("TZP-U", V_A, Map.of("grain_length", "extra-long-supplier-text"))));

        assertThat(keys(out.get(0))).containsExactly("grain_length");
        assertThat(keys(out.get(1))).as("accepted under H-11 is not published").isEmpty();
    }

    @Test
    void a_stored_value_that_contradicts_its_declared_type_is_suppressed() {
        policy(V_A, "v3", "pack_size:1:Pack", "aged:2:Aged");
        List<ConsumerProductResponse> out = projection.projectAll(List.of(
                product("TZP-T", V_A, Map.of("pack_size", "five", "pack_unit", "kg", "aged", true))));

        assertThat(keys(out.get(0))).containsExactly("aged");
    }

    @Test
    void a_paired_quantity_publishes_atomically_and_a_missing_or_bad_unit_suppresses_the_pair() {
        policy(V_A, "v3", "pack_size:1:Pack", "pack_unit:2:Unit", "aged:3:Aged");
        List<ConsumerProductResponse> out = projection.projectAll(List.of(
                product("TZP-OK", V_A, Map.of("pack_size", 5, "pack_unit", "kg", "aged", true)),
                product("TZP-BARE", V_A, Map.of("pack_size", 5, "aged", true)),
                product("TZP-BAD", V_A, Map.of("pack_size", 5, "pack_unit", "furlongs", "aged", true))));

        assertThat(keys(out.get(0))).as("the unit rides on the quantity, never alone").containsExactly("pack_size", "aged");
        assertThat(out.get(0).attributes().get(0).value()).isEqualTo(5);
        assertThat(out.get(0).attributes().get(0).unit()).isEqualTo("kg");
        assertThat(keys(out.get(1))).as("never a bare quantity").containsExactly("aged");
        assertThat(keys(out.get(2))).as("an unratified unit cannot be rescued by the number alone").containsExactly("aged");
    }

    @Test
    void a_superseded_paired_unit_definition_does_not_suppress_a_live_attribute() {
        db.getCollection("attribute_definitions").insertOne(
                new Document("key", "legacy_size").append("version", 1).append("type", "number")
                        .append("governance", "descriptive").append("status", "superseded")
                        .append("paired_unit", "aged"));
        policy(V_A, "v3", "aged:1:Aged");

        assertThat(keys(projection.projectAll(List.of(product("TZP-S", V_A, Map.of("aged", true)))).get(0)))
                .as("only an ACTIVE definition may mark a key as somebody's unit")
                .containsExactly("aged");
    }

    @Test
    void the_highest_active_version_of_a_definition_is_the_one_consulted() {
        // v2 of grain_length ratifies a different vocabulary; v1 is superseded.
        db.getCollection("attribute_definitions").updateOne(new Document("key", "grain_length"),
                new Document("$set", new Document("status", "superseded")));
        db.getCollection("attribute_definitions").insertOne(
                new Document("key", "grain_length").append("version", 2).append("type", "enum_open")
                        .append("governance", "descriptive").append("status", "active")
                        .append("known_values", List.of("extra-long")));
        db.getCollection("attribute_definitions").insertOne(
                new Document("key", "grain_length").append("version", 3).append("type", "enum_open")
                        .append("governance", "descriptive").append("status", "draft")
                        .append("known_values", List.of("draft-only")));
        policy(V_A, "v3", "grain_length:1:Grain");
        List<ConsumerProductResponse> out = projection.projectAll(List.of(
                product("TZP-V1", V_A, Map.of("grain_length", "long")),
                product("TZP-V2", V_A, Map.of("grain_length", "extra-long")),
                product("TZP-V3", V_A, Map.of("grain_length", "draft-only"))));

        assertThat(keys(out.get(0))).as("v1 is superseded: its vocabulary no longer publishes").isEmpty();
        assertThat(keys(out.get(1))).as("v2 is the highest ACTIVE version").containsExactly("grain_length");
        assertThat(keys(out.get(2))).as("v3 is a draft, not active, and is never consulted").isEmpty();
    }
}
