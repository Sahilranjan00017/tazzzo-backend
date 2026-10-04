package com.tazzzo.catalog.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Released migration V0001's checksum input is immutable (DB-4 hardening, M3). Its definition comes from its own frozen
 * contract, never from the live {@code SchemaBootstrap.COLLECTIONS} / {@code IndexCatalog.BASELINE}, which describe the
 * CURRENT schema and will grow. If V0001's checksum followed them, adding a collection would make every already-migrated
 * database refuse the new application version although V0001 itself was never touched.
 */
class BaselineFrozenContractTest {

    /** The checksums recorded by every database that has applied V0001 (definition only, and the full stored checksum). */
    static final String V0001_DEFINITION_SHA256 = "6e872b65f7d9186038025b5906a1be7841becc7fec1a3adb43589c83216fd9e9";
    static final String V0001_STORED_CHECKSUM = "3b703e4a08a7ad8f7a400323631587675238e6db6db48c3f06826ae6f0a695cb";

    private static BaselineSchemaMigration released() {
        return new BaselineSchemaMigration(null);
    }

    @Test
    void the_released_checksum_is_exactly_the_one_every_migrated_database_already_stored() {
        BaselineSchemaMigration v1 = released();
        assertThat(Checksums.sha256(v1.definition())).isEqualTo(V0001_DEFINITION_SHA256);
        assertThat(v1.checksum()).as("the value the history stores and compares").isEqualTo(V0001_STORED_CHECKSUM);
    }

    @Test
    void the_frozen_contract_has_its_released_size() {
        assertThat(BaselineV0001Contract.COLLECTIONS).hasSize(49).doesNotHaveDuplicates();
        assertThat(BaselineV0001Contract.INDEXES).hasSize(48);
        assertThat(BaselineV0001Contract.INDEXES.stream().map(IndexSpec::describe).toList()).doesNotHaveDuplicates();
    }

    @Test
    void the_migration_uses_its_frozen_lists_and_not_the_live_constants() {
        BaselineSchemaMigration v1 = released();
        assertThat(v1.collections()).isSameAs(BaselineV0001Contract.COLLECTIONS);
        assertThat(v1.collections()).isNotSameAs(com.tazzzo.catalog.schema.SchemaBootstrap.COLLECTIONS);
    }

    @Test
    void a_new_collection_in_the_live_schema_cannot_change_the_released_checksum() {
        // The injected contract stands in for "the live constants grew": the definition follows ITS input, so the only way
        // for V0001's checksum to move is to edit the frozen contract itself. The production instance reads only that.
        List<String> grown = new ArrayList<>(BaselineV0001Contract.COLLECTIONS);
        grown.add("a_hypothetical_future_collection");
        BaselineSchemaMigration other = new BaselineSchemaMigration(null, grown, BaselineV0001Contract.INDEXES);
        assertThat(other.checksum()).isNotEqualTo(V0001_STORED_CHECKSUM);
        assertThat(released().checksum()).isEqualTo(V0001_STORED_CHECKSUM);
    }

    @Test
    void a_new_index_in_the_live_catalog_cannot_change_the_released_checksum() {
        List<IndexSpec> grown = new ArrayList<>(BaselineV0001Contract.INDEXES);
        LinkedHashMap<String, Integer> keys = new LinkedHashMap<>();
        keys.put("future_field", 1);
        grown.add(new IndexSpec("products", null, keys, false, false, null, null));
        BaselineSchemaMigration other = new BaselineSchemaMigration(null, BaselineV0001Contract.COLLECTIONS, grown);
        assertThat(other.checksum()).isNotEqualTo(V0001_STORED_CHECKSUM);
        assertThat(released().checksum()).isEqualTo(V0001_STORED_CHECKSUM);
    }

    @Test
    void editing_the_frozen_definition_itself_changes_the_checksum() {
        List<String> fewer = new ArrayList<>(BaselineV0001Contract.COLLECTIONS);
        fewer.remove("memberships");
        assertThat(new BaselineSchemaMigration(null, fewer, BaselineV0001Contract.INDEXES).checksum()).isNotEqualTo(V0001_STORED_CHECKSUM);
        List<IndexSpec> altered = new ArrayList<>(BaselineV0001Contract.INDEXES);
        altered.remove(0);
        assertThat(new BaselineSchemaMigration(null, BaselineV0001Contract.COLLECTIONS, altered).checksum()).isNotEqualTo(V0001_STORED_CHECKSUM);
        // and the registry's own append-only pin refuses any edit to the released definition
        assertThat(MigrationRegistryTest.RELEASED.get("V0001__baseline_schema")).endsWith(V0001_DEFINITION_SHA256);
    }

    @Test
    void the_baseline_migration_source_never_mentions_the_live_schema_constants() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/tazzzo/catalog/migration/BaselineSchemaMigration.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        assertThat(src).as("V0001 must derive only from BaselineV0001Contract").doesNotContain("SchemaBootstrap.COLLECTIONS")
                .doesNotContain("IndexCatalog").doesNotContain("MANAGED");
        String contract = Files.readString(Path.of("src/main/java/com/tazzzo/catalog/migration/BaselineV0001Contract.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        assertThat(contract).as("the frozen contract is literal data, not a view of the live constants")
                .doesNotContain("SchemaBootstrap").doesNotContain("IndexCatalog");
    }

    @Test
    void the_default_registry_stays_append_only() {
        // a collection or index added to the live schema is a NEW migration (V0008+), never an edit of V0001
        assertThat(Migrations.defaults(null, null).stream().map(Migration::id).toList())
                .containsExactlyElementsOf(MigrationRegistryTest.RELEASED.keySet());
    }
}
