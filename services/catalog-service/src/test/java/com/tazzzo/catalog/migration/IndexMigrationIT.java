package com.tazzzo.catalog.migration;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Index creation, adoption, conflict handling, duplicate preflights, drop and replace — real MongoDB 7. */
class IndexMigrationIT extends AbstractMigrationIT {

    private static final String CURSOR = "V0002__products_vertical_id_cursor_index";
    private static final Document CURSOR_KEYS = new Document("classification.vertical_id", 1).append("_id", 1);

    /** A database created exactly the way the legacy bootstrap created it. */
    private MongoDatabase legacyDatabase() {
        MongoDatabase d = scratch();
        schemaBootstrap.bootstrap(d);
        return d;
    }

    private MigrationRunner only(MongoDatabase d, String... ids) {
        Set<String> wanted = Set.of(ids);
        List<Migration> picked = Migrations.defaults(schemaBootstrap, taxonomyLoader).stream()
                .filter(m -> wanted.contains(m.id())).toList();
        assertThat(picked).hasSize(ids.length);
        return runner(d, picked);
    }

    private MigrationRunner.RunReport run(MongoDatabase d, MigrationRunner r) {
        return r.apply(target(d), MigrationRunner.Selection.all(), apply());
    }

    /** Enables ONLY the named disabled migrations (an unknown id would be rejected as INVALID_SELECTION). */
    private MigrationRunner.RunReport runEnabling(MongoDatabase d, MigrationRunner r, String... enabledIds) {
        return r.apply(target(d), MigrationRunner.Selection.all().withEnabled(Set.of(enabledIds)), apply());
    }

    private static LinkedHashMap<String, Integer> keys(Object... kv) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    // ---- products cursor index: the four deployment states ------------------------------------

    @Test
    void A_absent_the_index_is_created() {
        MongoDatabase d = legacyDatabase();
        assertThat(IndexCatalog.PRODUCT_VERTICAL_CURSOR_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.ABSENT);

        MigrationRunner.RunReport r = run(d, only(d, CURSOR));

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.APPLIED_NOW);
        assertThat(IndexCatalog.PRODUCT_VERTICAL_CURSOR_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(history(d, CURSOR).getBoolean("adopted")).isFalse();
    }

    @Test
    void B_the_exact_index_already_present_is_adopted_without_any_change() {
        MongoDatabase d = legacyDatabase();
        IndexCatalog.PRODUCT_VERTICAL_CURSOR_SPEC.create(d);
        String before = indexSnapshot(d);

        MigrationRunner.RunReport r = run(d, only(d, CURSOR));

        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
        assertThat(indexSnapshot(d)).isEqualTo(before);
        assertThat(history(d, CURSOR).getBoolean("adopted")).isTrue();
    }

    @Test
    void C_the_same_keys_under_another_name_are_adopted_and_never_hit_IndexOptionsConflict() {
        MongoDatabase d = legacyDatabase();
        d.getCollection("products").createIndex(CURSOR_KEYS, new IndexOptions().name("hand_made_vertical_cursor"));
        String before = indexSnapshot(d);
        assertThat(IndexCatalog.PRODUCT_VERTICAL_CURSOR_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.SAME_KEYS_OTHER_NAME);

        MigrationRunner.RunReport dry = only(d, CURSOR).dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(dry.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.WOULD_ADOPT);
        assertThat(dry.steps().get(0).notes()).anyMatch(n -> n.contains("hand_made_vertical_cursor"));

        // creating it by name now WOULD throw — which is exactly what the migration must not do
        assertThatThrownBy(() -> IndexCatalog.PRODUCT_VERTICAL_CURSOR_SPEC.create(d))
                .hasMessageContaining("already exists");

        MigrationRunner.RunReport r = run(d, only(d, CURSOR));

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
        assertThat(indexSnapshot(d)).as("nothing created, nothing dropped, nothing renamed").isEqualTo(before);
        assertThat(history(d, CURSOR).getString("note")).contains("adopted");
        // the legacy bootstrap no longer creates this index, so a bootstrap on such a database cannot abort startup either
        schemaBootstrap.bootstrap(d);
        assertThat(indexSnapshot(d)).isEqualTo(before);
    }

    @Test
    void D1_the_name_is_taken_by_a_different_definition_is_blocked_and_nothing_changes() {
        MongoDatabase d = legacyDatabase();
        d.getCollection("products").createIndex(CURSOR_KEYS, new IndexOptions().name(IndexCatalog.PRODUCT_VERTICAL_CURSOR).sparse(true));
        String before = indexSnapshot(d);

        MigrationRunner.RunReport r = run(d, only(d, CURSOR));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers()).anyMatch(b -> b.contains("different definition"));
        assertThat(indexSnapshot(d)).as("a conflicting index is never dropped or altered").isEqualTo(before);
        assertThat(history(d, CURSOR).getString("status")).isEqualTo("BLOCKED");
    }

    @Test
    void D2_the_same_keys_with_different_options_under_another_name_are_blocked() {
        MongoDatabase d = legacyDatabase();
        d.getCollection("products").createIndex(CURSOR_KEYS, new IndexOptions().name("other_name").unique(true));
        String before = indexSnapshot(d);

        MigrationRunner.RunReport r = run(d, only(d, CURSOR));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers()).anyMatch(b -> b.contains("other_name"));
        assertThat(indexSnapshot(d)).isEqualTo(before);
    }

    // ---- baseline ------------------------------------------------------------------------------

    @Test
    void the_baseline_adopts_a_database_created_by_the_legacy_bootstrap_without_mutation() {
        MongoDatabase d = legacyDatabase();
        String before = indexSnapshot(d);
        List<String> collections = collectionNames(d);

        MigrationRunner.RunReport r = run(d, only(d, "V0001__baseline_schema"));

        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
        assertThat(indexSnapshot(d)).isEqualTo(before);
        assertThat(collectionNames(d)).containsAll(collections);
    }

    @Test
    void the_baseline_blocks_on_a_conflicting_existing_index_and_creates_no_business_collection() {
        MongoDatabase d = scratch();
        d.getCollection("customers").createIndex(new Document("phoneNormalized", 1), new IndexOptions().name("customer_one_per_phone")); // NOT unique

        MigrationRunner.RunReport r = run(d, only(d, "V0001__baseline_schema"));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers()).anyMatch(b -> b.contains("customer_one_per_phone"));
        assertThat(collectionNames(d)).as("only the pre-existing collection and the two bookkeeping collections")
                .containsExactlyInAnyOrder("customers", MigrationHistory.COLLECTION, MigrationLock.COLLECTION);
    }

    @Test
    void the_baseline_adopts_an_equivalent_index_under_another_name_and_creates_the_rest() {
        MongoDatabase d = scratch();
        d.getCollection("customers").createIndex(new Document("phoneNormalized", 1), new IndexOptions().name("uniq_phone").unique(true));

        MigrationRunner.RunReport r = run(d, only(d, "V0001__baseline_schema"));

        assertThat(r.ok()).as(r.render()).isTrue();
        Set<String> names = new java.util.TreeSet<>();
        d.getCollection("customers").listIndexes().forEach(i -> names.add(i.getString("name")));
        assertThat(names).containsExactlyInAnyOrder("_id_", "uniq_phone");
        for (IndexSpec s : IndexCatalog.BASELINE) {
            assertThat(s.inspect(d).state()).as(s.describe()).isIn(IndexSpec.State.EXACT, IndexSpec.State.SAME_KEYS_OTHER_NAME);
        }
    }

    @Test
    void the_baseline_drops_the_superseded_products_prefix_index_only_after_the_wider_one_exists() {
        MongoDatabase d = scratch();
        d.getCollection("products").createIndex(new Document("classification.vertical_id", 1).append("lifecycle", 1)
                .append("classification.status", 1));
        assertThat(schemaBootstrap.hasSupersededProductsPrefixIndex(d)).isTrue();

        MigrationRunner.RunReport dry = only(d, "V0001__baseline_schema").dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(dry.steps().get(0).operations()).anyMatch(o -> o.contains("drop superseded products prefix index"));

        MigrationRunner.RunReport r = run(d, only(d, "V0001__baseline_schema"));

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(schemaBootstrap.hasSupersededProductsPrefixIndex(d)).isFalse();
        assertThat(IndexCatalog.BASELINE.get(0).inspect(d).state()).as("PAG-2 index present").isEqualTo(IndexSpec.State.EXACT);
    }

    // ---- unique indexes: duplicate preflight ---------------------------------------------------

    @Test
    void the_evidence_link_unique_index_is_not_created_while_duplicates_exist_and_no_data_is_touched() {
        MongoDatabase d = legacyDatabase();
        var links = d.getCollection("evidence_links");
        links.insertMany(List.of(
                new Document("evidence_id", "EV-1").append("product_id", "P-1").append("link_type", "claim"),
                new Document("evidence_id", "EV-1").append("product_id", "P-1").append("link_type", "claim"),
                new Document("evidence_id", "EV-1").append("product_id", "P-2").append("link_type", "claim")));
        String v = "V0005__evidence_links_unique_link";

        MigrationRunner.RunReport dry = only(d, v).dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(dry.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);

        MigrationRunner.RunReport r = run(d, only(d, v));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers().get(0)).contains("duplicates exist").contains("EV-1").contains("never deleted or merged");
        assertThat(IndexCatalog.EVIDENCE_LINK_UNIQUE_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.ABSENT);
        assertThat(links.countDocuments()).as("business data is never deleted or merged automatically").isEqualTo(3);

        // an operator resolves the duplicate; the very same migration then proceeds
        links.deleteOne(new Document("evidence_id", "EV-1").append("product_id", "P-1"));
        MigrationRunner.RunReport retry = run(d, only(d, v));
        assertThat(retry.ok()).as(retry.render()).isTrue();
        assertThat(IndexCatalog.EVIDENCE_LINK_UNIQUE_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThatThrownBy(() -> links.insertOne(new Document("evidence_id", "EV-1").append("product_id", "P-2").append("link_type", "claim")))
                .isInstanceOf(MongoWriteException.class);
    }

    @Test
    void the_taxonomy_sibling_unique_index_considers_only_active_nodes() {
        MongoDatabase d = legacyDatabase();
        var nodes = d.getCollection("taxonomy_nodes");
        nodes.insertMany(List.of(
                new Document("_id", "N-1").append("parent_id", "P").append("name", "Dairy").append("status", "active"),
                new Document("_id", "N-2").append("parent_id", "P").append("name", "Dairy").append("status", "active")));
        String v = "V0006__taxonomy_nodes_unique_active_sibling_name";

        MigrationRunner.RunReport blocked = run(d, only(d, v));
        assertThat(blocked.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(blocked.steps().get(0).blockers().get(0)).contains("duplicates exist");
        assertThat(IndexCatalog.TAXONOMY_SIBLING_UNIQUE_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.ABSENT);
        assertThat(nodes.countDocuments()).isEqualTo(2);

        // deprecating one of them (an operator/business decision, made outside the migration) removes the conflict
        nodes.updateOne(new Document("_id", "N-2"), new Document("$set", new Document("status", "deprecated")));
        MigrationRunner.RunReport ok = run(d, only(d, v));
        assertThat(ok.ok()).as(ok.render()).isTrue();
        assertThatThrownBy(() -> nodes.insertOne(new Document("_id", "N-3").append("parent_id", "P").append("name", "Dairy").append("status", "active")))
                .isInstanceOf(MongoWriteException.class);
        nodes.insertOne(new Document("_id", "N-4").append("parent_id", "P").append("name", "Dairy").append("status", "deprecated")); // free
    }

    @Test
    void a_unique_index_that_already_exists_is_adopted() {
        MongoDatabase d = legacyDatabase();
        IndexCatalog.EVIDENCE_LINK_UNIQUE_SPEC.create(d);
        MigrationRunner.RunReport r = run(d, only(d, "V0005__evidence_links_unique_link"));
        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
    }

    // ---- drop candidates -----------------------------------------------------------------------

    @Test
    void drop_candidates_are_registered_but_never_run_unless_explicitly_enabled() {
        MongoDatabase d = legacyDatabase();
        MigrationRunner r = runner(d, Migrations.defaults(schemaBootstrap, taxonomyLoader));
        MigrationRunner.RunReport plain = r.apply(target(d), MigrationRunner.Selection.schemaOnly(), apply());
        assertThat(plain.steps()).noneMatch(s -> s.id().startsWith("V01"));
        assertThat(IndexCatalog.SESSION_BY_CUSTOMER_SPEC.inspect(d).state()).as("still there").isEqualTo(IndexSpec.State.EXACT);
        assertThat(IndexCatalog.CANONICAL_KEYS_PRODUCT_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
    }

    @Test
    void an_enabled_drop_removes_exactly_the_reviewed_index_and_records_how_to_restore_it() {
        MongoDatabase d = legacyDatabase();
        String v = "V0101__drop_unused_session_by_customer_index";
        MigrationRunner.RunReport r = runEnabling(d, only(d, v), v);
        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(IndexCatalog.SESSION_BY_CUSTOMER_SPEC.namedIndexPresent(d)).isFalse();
        assertThat(IndexCatalog.CANONICAL_KEYS_PRODUCT_SPEC.inspect(d).state()).as("other indexes untouched").isEqualTo(IndexSpec.State.EXACT);
        assertThat(history(d, v).get("rollbackInfo", Document.class).getString("recreate")).contains("session_by_customer");
        assertThat(runEnabling(d, only(d, v), v).steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ALREADY_APPLIED);
    }

    @Test
    void a_drop_refuses_an_index_that_is_not_the_exact_reviewed_definition() {
        MongoDatabase d = legacyDatabase();
        d.getCollection("customer_sessions").dropIndex("session_by_customer");
        d.getCollection("customer_sessions").createIndex(new Document("customerId", 1), new IndexOptions().name("by_cust"));
        String before = indexSnapshot(d);

        MigrationRunner.RunReport r = runEnabling(d, only(d, "V0101__drop_unused_session_by_customer_index"), "V0101__drop_unused_session_by_customer_index");

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers().get(0)).contains("by_cust").contains("not the exact reviewed definition");
        assertThat(indexSnapshot(d)).isEqualTo(before);
    }

    @Test
    void dropping_an_index_that_is_already_gone_is_adopted() {
        MongoDatabase d = legacyDatabase();
        d.getCollection("canonical_keys").dropIndex("product_id_1");
        MigrationRunner.RunReport r = runEnabling(d, only(d, "V0102__drop_unused_canonical_keys_product_id_index"), "V0102__drop_unused_canonical_keys_product_id_index");
        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
    }

    // ---- replace / rename ----------------------------------------------------------------------

    private IndexSpec spec(String name, boolean unique, Object... kv) {
        return new IndexSpec("ren", name, keys(kv), unique, false, null, null);
    }

    @Test
    void a_pure_rename_swaps_the_name_and_is_idempotent() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("old_name", false, "a", 1);
        IndexSpec to = spec("new_name", false, "a", 1);
        from.create(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "rename", from, to, true);
        assertThat(m.preflight(d).operations()).containsExactly("drop index old_name", "create index " + to.describe());

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(from.namedIndexPresent(d)).isFalse();
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(m.preflight(d).status()).as("a re-evaluated rename is already satisfied").isEqualTo(Preflight.Status.ALREADY_SATISFIED);
    }

    @Test
    void a_redefinition_is_create_before_drop() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("old_idx", false, "a", 1);
        IndexSpec to = spec("new_idx", false, "a", 1, "b", 1);
        from.create(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "replace", from, to, true);
        assertThat(m.preflight(d).operations()).containsExactly("create index " + to.describe(), "drop index old_idx");
        assertThat(runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(from.namedIndexPresent(d)).isFalse();
    }

    @Test
    void a_replace_is_blocked_and_keeps_the_old_index_when_the_target_name_is_taken_by_something_else() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("old_idx", false, "a", 1);
        IndexSpec to = spec("new_idx", false, "a", 1, "b", 1);
        from.create(d);
        d.getCollection("ren").createIndex(new Document("zzz", 1), new IndexOptions().name("new_idx"));
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "replace", from, to, true);

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(from.namedIndexPresent(d)).as("the old index is untouched").isTrue();
    }

    @Test
    void index_inspection_distinguishes_two_different_partial_filters_on_the_same_keys() {
        MongoDatabase d = scratch();
        // like otp_one_delivering_per_phone / otp_one_active_per_phone: same keys, different partial filters, both legitimate
        IndexSpec a = new IndexSpec("ren", "only_a", keys("k", 1), true, false, new Document("a", true), null);
        IndexSpec b = new IndexSpec("ren", "only_b", keys("k", 1), true, false, new Document("b", true), null);
        a.create(d);
        assertThat(b.inspect(d).state()).as("a different partial filter is a different index, not a conflict").isEqualTo(IndexSpec.State.ABSENT);
        b.create(d);
        assertThat(a.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(b.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
    }

    // ---- key ORDER is part of an index's identity ----------------------------------------------

    @Test
    void key_order_is_significant_in_index_inspection() {
        MongoDatabase d = scratch();
        IndexSpec ab = new IndexSpec("ord", "ab", keys("a", 1, "b", 1), false, false, null, null);
        d.getCollection("ord").createIndex(new Document("b", 1).append("a", 1), new IndexOptions().name("ba"));

        assertThat(ab.inspect(d).state()).as("{b,a} is a DIFFERENT index from {a,b}, not an equivalent one")
                .isEqualTo(IndexSpec.State.ABSENT);

        // same NAME, reversed keys: the name is taken by a different definition
        IndexSpec sameNameReversed = new IndexSpec("ord", "ba", keys("a", 1, "b", 1), false, false, null, null);
        assertThat(sameNameReversed.inspect(d).state()).isEqualTo(IndexSpec.State.CONFLICT);

        // direction is part of the identity too
        IndexSpec descending = new IndexSpec("ord", "b_desc_a", keys("b", -1, "a", 1), false, false, null, null);
        assertThat(descending.inspect(d).state()).isEqualTo(IndexSpec.State.ABSENT);

        IndexSpec.State before = ab.inspect(d).state();
        ab.create(d);
        assertThat(ab.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(before).isEqualTo(IndexSpec.State.ABSENT);
        assertThat(ab.canCoexistWith(new IndexSpec("ord", "ba2", keys("b", 1, "a", 1), false, false, null, null))).isTrue();
    }

    @Test
    void a_create_migration_does_not_adopt_a_reversed_key_index_and_creates_the_right_one() {
        MongoDatabase d = scratch();
        d.getCollection("evidence_links").createIndex(new Document("link_type", 1).append("product_id", 1).append("evidence_id", 1),
                new IndexOptions().name("reversed_triple").unique(true)); // same fields, REVERSED order
        MigrationRunner.RunReport r = run(d, only(d, "V0005__evidence_links_unique_link"));

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(r.steps().get(0).status()).as("a reversed-order index must NOT be adopted").isEqualTo(MigrationRunner.StepStatus.APPLIED_NOW);
        assertThat(IndexCatalog.EVIDENCE_LINK_UNIQUE_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(d.getCollection("evidence_links").listIndexes().into(new java.util.ArrayList<>()).stream()
                .map(i -> i.getString("name"))).contains("reversed_triple", IndexCatalog.EVIDENCE_LINK_UNIQUE);
    }

    @Test
    void validation_fails_when_only_a_reversed_key_index_exists() {
        MongoDatabase d = scratch();
        d.getCollection("evidence_links").createIndex(new Document("link_type", 1).append("product_id", 1).append("evidence_id", 1),
                new IndexOptions().name("reversed_triple").unique(true));
        CreateIndexMigration m = new CreateIndexMigration("T", "t", List.of(IndexCatalog.EVIDENCE_LINK_UNIQUE_SPEC), null);
        assertThat(m.validate(d)).as("a missing index must not be recorded as applied because a reversed one exists").isNotEmpty();
    }

    @Test
    void the_baseline_creates_a_missing_index_even_when_a_reversed_one_exists() {
        MongoDatabase d = scratch();
        d.getCollection("evidence_links").createIndex(new Document("active", 1).append("evidence_id", 1), new IndexOptions().name("rev_ev_active"));
        IndexSpec wanted = IndexCatalog.BASELINE.stream().filter(s -> s.collection().equals("evidence_links")
                && s.keys().keySet().iterator().next().equals("evidence_id")).findFirst().orElseThrow();
        assertThat(wanted.inspect(d).state()).isEqualTo(IndexSpec.State.ABSENT);
        assertThat(run(d, only(d, "V0001__baseline_schema")).ok()).isTrue();
        assertThat(wanted.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
    }

    // ---- replace / rename: resumable, and options-only redefinitions --------------------------

    @Test
    void an_interrupted_rename_resumes_instead_of_blocking_forever() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("old_name", false, "a", 1);
        IndexSpec to = spec("new_name", false, "a", 1);
        from.create(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "rename", from, to, true);
        // a crash between the drop and the create leaves neither index
        d.getCollection("ren").dropIndex("old_name");
        assertThat(from.namedIndexPresent(d)).isFalse();
        assertThat(to.namedIndexPresent(d)).isFalse();

        Preflight pf = m.preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.READY);
        assertThat(pf.operations()).containsExactly("create index " + to.describe());
        assertThat(pf.notes()).anyMatch(n -> n.contains("interrupted"));

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(from.namedIndexPresent(d)).isFalse();
    }

    @Test
    void a_rename_is_blocked_when_an_equivalent_index_exists_under_a_third_name() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("old_name", false, "a", 1);
        IndexSpec to = spec("new_name", false, "a", 1);
        d.getCollection("ren").createIndex(new Document("a", 1), new IndexOptions().name("somebody_elses"));
        Preflight pf = new ReplaceIndexMigration("T1", "rename", from, to, true).preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.BLOCKED);
        assertThat(pf.blockers().get(0)).contains("somebody_elses").contains("neither the source");
    }

    @Test
    void changing_only_the_options_of_an_index_is_drop_then_create_not_an_unreachable_conflict() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx_a", false, "a", 1);
        IndexSpec to = spec("idx_a_unique", true, "a", 1);
        from.create(d);
        d.getCollection("ren").insertMany(List.of(new Document("a", 1), new Document("a", 2)));
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "make unique", from, to, true,
                DuplicateCheck.byFields("ren", null, "a"));

        assertThat(m.definition()).contains("drop-then-create");
        Preflight pf = m.preflight(d);
        assertThat(pf.status()).as("same keys, different options cannot coexist, so this is a legitimate drop-then-create").isEqualTo(Preflight.Status.READY);
        assertThat(pf.operations()).containsExactly("drop index idx_a", "create index " + to.describe());

        assertThat(runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(from.namedIndexPresent(d)).isFalse();
    }

    @Test
    void making_an_index_unique_is_blocked_by_duplicates_and_the_old_index_is_kept() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx_a", false, "a", 1);
        IndexSpec to = spec("idx_a_unique", true, "a", 1);
        from.create(d);
        d.getCollection("ren").insertMany(List.of(new Document("a", 1), new Document("a", 1)));
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "make unique", from, to, true,
                DuplicateCheck.byFields("ren", null, "a"));

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers().get(0)).contains("duplicates exist").contains("never deleted or merged");
        assertThat(from.namedIndexPresent(d)).as("the old index is untouched").isTrue();
        assertThat(d.getCollection("ren").countDocuments()).as("no business data touched").isEqualTo(2);
    }

    @Test
    void two_different_partial_filters_on_the_same_keys_replace_create_before_drop() {
        MongoDatabase d = scratch();
        IndexSpec from = new IndexSpec("ren", "only_x", keys("k", 1), true, false, new Document("x", true), null);
        IndexSpec to = new IndexSpec("ren", "only_y", keys("k", 1), true, false, new Document("y", true), null);
        from.create(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "swap partial", from, to, true);
        assertThat(from.canCoexistWith(to)).isTrue();
        assertThat(m.definition()).contains("create-before-drop");
        assertThat(m.preflight(d).operations()).containsExactly("create index " + to.describe(), "drop index only_x");
        assertThat(runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
    }

    @Test
    void a_blocked_real_migration_stops_the_run_and_the_next_one_is_not_run() {
        MongoDatabase d = legacyDatabase();
        d.getCollection("evidence_links").insertMany(List.of(
                new Document("evidence_id", "EV-1").append("product_id", "P-1").append("link_type", "claim"),
                new Document("evidence_id", "EV-1").append("product_id", "P-1").append("link_type", "claim")));
        MigrationRunner.RunReport r = run(d, only(d, "V0005__evidence_links_unique_link", "V0006__taxonomy_nodes_unique_active_sibling_name"));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.BLOCKED);
        assertThat(r.steps().get(1).id()).isEqualTo("V0006__taxonomy_nodes_unique_active_sibling_name");
        assertThat(r.steps().get(1).status()).as("a real migration after a blocked one is NOT_RUN").isEqualTo(MigrationRunner.StepStatus.NOT_RUN);
        assertThat(IndexCatalog.TAXONOMY_SIBLING_UNIQUE_SPEC.inspect(d).state()).isEqualTo(IndexSpec.State.ABSENT);
        assertThat(history(d, "V0006__taxonomy_nodes_unique_active_sibling_name")).isNull();
    }

    // ---- same-NAME replacement (the most natural redefinition: keep the name, change the options) ----

    @Test
    void a_same_name_options_only_redefinition_applies_validates_and_is_idempotent() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx_a", false, "a", 1);
        IndexSpec to = spec("idx_a", true, "a", 1); // SAME name, now unique
        from.create(d);
        d.getCollection("ren").insertMany(List.of(new Document("a", 1), new Document("a", 2)));
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "make idx_a unique", from, to, true,
                DuplicateCheck.byFields("ren", null, "a"));
        assertThat(m.definition()).as("a shared name can never be create-before-drop").contains("drop-then-create");
        assertThat(m.preflight(d).status()).isEqualTo(Preflight.Status.READY);

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(r.steps().get(0).status()).as("not FAILED: the change worked and must validate").isEqualTo(MigrationRunner.StepStatus.APPLIED_NOW);
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
        assertThat(m.validate(d)).isEmpty();
        assertThat(history(d, "T1").getString("status")).isEqualTo("APPLIED");
        // idempotent both through the history and when re-evaluated from the live state alone
        assertThat(runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()).steps().get(0).status())
                .isEqualTo(MigrationRunner.StepStatus.ALREADY_APPLIED);
        assertThat(m.preflight(d).status()).as("no history needed: the live state already satisfies it").isEqualTo(Preflight.Status.ALREADY_SATISFIED);
        assertThatThrownBy(() -> d.getCollection("ren").insertOne(new Document("a", 1))).isInstanceOf(MongoWriteException.class);
    }

    @Test
    void a_same_name_redefinition_to_unique_is_blocked_by_duplicates_and_keeps_the_old_index() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx_a", false, "a", 1);
        IndexSpec to = spec("idx_a", true, "a", 1);
        from.create(d);
        d.getCollection("ren").insertMany(List.of(new Document("a", 7), new Document("a", 7)));
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "make idx_a unique", from, to, true,
                DuplicateCheck.byFields("ren", null, "a"));

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(from.inspect(d).state()).as("the old (non-unique) index is untouched").isEqualTo(IndexSpec.State.EXACT);
        assertThat(d.getCollection("ren").countDocuments()).isEqualTo(2);
    }

    @Test
    void a_same_name_redefinition_with_different_keys_is_drop_then_create() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx", false, "a", 1);
        IndexSpec to = spec("idx", false, "a", 1, "b", 1); // the keys change but the name is kept
        from.create(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "widen idx", from, to, true);
        assertThat(from.canCoexistWith(to)).as("different keys could coexist, but the NAME cannot").isTrue();
        assertThat(m.definition()).contains("drop-then-create");

        assertThat(runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
    }

    @Test
    void an_interrupted_same_name_replacement_resumes() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx_a", false, "a", 1);
        IndexSpec to = spec("idx_a", true, "a", 1);
        from.create(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "make unique", from, to, true,
                DuplicateCheck.byFields("ren", null, "a"));
        d.getCollection("ren").dropIndex("idx_a"); // a crash between the drop and the create
        Preflight pf = m.preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.READY);
        assertThat(pf.operations()).containsExactly("create index " + to.describe());
        assertThat(runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(to.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
    }

    @Test
    void apply_rechecks_after_the_drop_and_never_proceeds_silently_if_an_equivalent_index_appeared() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("old_name", false, "a", 1);
        IndexSpec to = spec("new_name", false, "a", 1);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "rename", from, to, true);
        // the source is gone and an equivalent index now exists under a third name (a race since the preflight)
        d.getCollection("ren").createIndex(new Document("a", 1), new IndexOptions().name("appeared_meanwhile"));
        assertThatThrownBy(() -> m.apply(d)).isInstanceOf(MigrationException.class).hasMessageContaining("appeared_meanwhile");
    }

    // ---- the state-matrix cases a sampling review missed -------------------------------------

    @Test
    void a_same_name_replacement_is_blocked_before_any_drop_when_an_equivalent_index_exists_under_a_third_name() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx", false, "a", 1);
        IndexSpec to = spec("idx", false, "a", 1, "b", 1); // widen, keeping the name
        from.create(d);
        d.getCollection("ren").createIndex(new Document("a", 1).append("b", 1), new IndexOptions().name("idx_ab")); // already equivalent
        String before = indexSnapshot(d);
        ReplaceIndexMigration m = new ReplaceIndexMigration("T1", "widen idx", from, to, true);

        Preflight pf = m.preflight(d);
        assertThat(pf.status()).as("must NOT say READY and then drop the source and fail").isEqualTo(Preflight.Status.BLOCKED);
        assertThat(pf.blockers().get(0)).contains("idx_ab").contains("nothing is dropped");

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(from.inspect(d).state()).as("the source was never dropped").isEqualTo(IndexSpec.State.EXACT);
        assertThat(indexSnapshot(d)).isEqualTo(before);
    }

    @Test
    void a_same_name_replacement_is_blocked_when_a_conflicting_index_exists_under_a_third_name() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx", false, "a", 1);
        IndexSpec to = spec("idx", false, "a", 1, "b", 1);
        from.create(d);
        // same keys as the target but DIFFERENT options, under another name
        d.getCollection("ren").createIndex(new Document("a", 1).append("b", 1), new IndexOptions().name("idx_ab_unique").unique(true));
        Preflight pf = new ReplaceIndexMigration("T1", "widen idx", from, to, true).preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.BLOCKED);
        assertThat(pf.blockers().get(0)).contains("different options").contains("idx_ab_unique");
        assertThat(from.inspect(d).state()).isEqualTo(IndexSpec.State.EXACT);
    }

    @Test
    void a_same_name_replacement_is_blocked_when_the_live_index_is_neither_the_source_nor_the_target() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx", false, "a", 1);
        IndexSpec to = spec("idx", true, "a", 1);
        d.getCollection("ren").createIndex(new Document("z", 1), new IndexOptions().name("idx")); // the name is held by something else
        Preflight pf = new ReplaceIndexMigration("T1", "make unique", from, to, true, DuplicateCheck.byFields("ren", null, "a")).preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.BLOCKED);
        assertThat(pf.blockers().get(0)).contains("neither the exact reviewed source nor the target");
        assertThat(d.getCollection("ren").listIndexes().into(new java.util.ArrayList<>()).stream().map(i -> i.getString("name")))
                .contains("idx");
    }

    @Test
    void a_blocked_same_name_resume_reports_the_real_reason() {
        MongoDatabase d = scratch();
        IndexSpec from = spec("idx", false, "a", 1);
        IndexSpec to = spec("idx", true, "a", 1);
        // the source is already gone; an index with the target's keys but different options exists under another name
        d.getCollection("ren").createIndex(new Document("a", 1), new IndexOptions().name("a_sparse").sparse(true));
        Preflight pf = new ReplaceIndexMigration("T1", "make unique", from, to, true, DuplicateCheck.byFields("ren", null, "a")).preflight(d);
        assertThat(pf.status()).isEqualTo(Preflight.Status.BLOCKED);
        assertThat(pf.blockers().get(0)).as("not the misleading 'an equivalent index exists'").contains("different options").contains("a_sparse");
    }

    @Test
    void a_replacement_that_makes_an_index_unique_must_carry_a_duplicate_check() {
        IndexSpec from = spec("idx_a", false, "a", 1);
        IndexSpec to = spec("idx_a_unique", true, "a", 1);
        assertThatThrownBy(() -> new ReplaceIndexMigration("T1", "make unique", from, to, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DuplicateCheck");
        // making an index non-unique, or leaving uniqueness unchanged, needs none
        new ReplaceIndexMigration("T2", "relax", spec("u", true, "a", 1), spec("n", false, "a", 1), true);
        new ReplaceIndexMigration("T3", "same", spec("x", false, "a", 1), spec("y", false, "a", 1), true);
    }
}
