package com.tazzzo.catalog.migration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration registry is the database's version history: ids are immutable, unique and ordered, and a released
 * migration's definition never changes (the history stores its checksum and refuses a mismatch). No database needed.
 */
class MigrationRegistryTest {

    /** id -> kind/default/definition checksum of every RELEASED migration. Append only: never edit or remove a row. */
    static final Map<String, String> RELEASED = new LinkedHashMap<>();

    static {
        RELEASED.put("V0001__baseline_schema", "SCHEMA|true|6e872b65f7d9186038025b5906a1be7841becc7fec1a3adb43589c83216fd9e9");
        RELEASED.put("V0002__products_vertical_id_cursor_index", "SCHEMA|true|b719f7b909fc5dcf25a15344fa1112082d5b76b658d50950381787eeaa6f0e4a");
        RELEASED.put("V0003__taxonomy_seed_0_9_0", "REFERENCE_INIT|true|a687c64f837f5e5b020875b74a5fa55354e25f5733b4089a3350afadf2e36931");
        RELEASED.put("V0004__seed_schemas_pack_fields_not_required", "DATA|true|a31446fe65f7f3d4eb0f083e86a5abb0183d9dc68613ecb055e1b54d87e40e99");
        RELEASED.put("V0005__evidence_links_unique_link", "SCHEMA|true|6c9f4fb5ede31c4e1e38b650f6d7ddb62d8d0918da62ad46d6c5d2aed6768ec2");
        RELEASED.put("V0006__taxonomy_nodes_unique_active_sibling_name", "SCHEMA|true|3e944ae4e2a17733dbb262d3aadb8140fe533917e82793e986516e1cc9c8ec4f");
        RELEASED.put("V0007__audit_read_partial_indexes", "SCHEMA|true|3bbe2d9a67dfb44ed348f43a87b389cfa18598005879763c542b13dbe547f43d");
        RELEASED.put("V0008__delivery_slot_indexes", "SCHEMA|true|b55296f6ecd22b9c7adc2df045cf0d5698763b511fd8e2a22f75a91a43fa9718");
        RELEASED.put("V0010__orders_by_customer_recent_index", "SCHEMA|true|f06415f5a51e692b0acc154d0ae64faa66dd6c1f0f8d66350ef20e137512079a");
        RELEASED.put("V0012__orders_staff_queue_indexes", "SCHEMA|true|d477eefcc64c2d6fb50cb37a09d662fbeed31ca45ee3704692c0bb208dfdb23f");
        RELEASED.put("V0011__support_case_indexes", "SCHEMA|true|8583b8f1a4f01a72d9405e82acbe3fde53af0145c9becc373414b3ad91d666d4");
        RELEASED.put("V0101__drop_unused_session_by_customer_index", "SCHEMA|false|0ed42be55eda44aa3b60891e4e52ef394aac6c94293a253da23d5de9e18b606d");
        RELEASED.put("V0102__drop_unused_canonical_keys_product_id_index", "SCHEMA|false|f846da155273d5338fa0ac4285c3cad0478cbc0b40bfe7dca8859136f8b15e36");
    }

    private static MigrationRunner runnerOf(List<Migration> registry) {
        return new MigrationRunner(null, registry, null, null, Clock.systemUTC());
    }

    @Test
    void a_duplicate_migration_id_stops_the_registry_from_being_built() {
        assertThatThrownBy(() -> runnerOf(List.of(new TestMigration("V0001__a"), new TestMigration("V0002__b"), new TestMigration("V0001__a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate migration id 'V0001__a'");
        // the same id with a different kind or definition is still the same version
        TestMigration other = new TestMigration("V0001__a", MigrationKind.DATA);
        other.definition = "different";
        assertThatThrownBy(() -> runnerOf(List.of(new TestMigration("V0001__a"), other))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_blank_migration_id_is_refused() {
        assertThatThrownBy(() -> runnerOf(List.of(new TestMigration(" ")))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank id");
    }

    @Test
    void a_valid_registry_is_accepted_in_any_input_order() {
        assertThatCode(() -> runnerOf(List.of(new TestMigration("V0003__c"), new TestMigration("V0001__a"), new TestMigration("V0002__b"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> runnerOf(List.of())).doesNotThrowAnyException();
    }

    @Test
    void the_default_registry_is_unique_well_formed_and_strictly_ordered() {
        List<Migration> defaults = Migrations.defaults(null, null);
        List<String> ids = defaults.stream().map(Migration::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        Pattern format = Pattern.compile("^V\\d{4}__[a-z0-9_]+$");
        ids.forEach(id -> assertThat(id).matches(format));
        assertThat(ids).as("sorted by id = deterministic version order").isSorted();
        List<Integer> versions = new ArrayList<>();
        ids.forEach(id -> versions.add(Integer.parseInt(id.substring(1, 5))));
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i)).as("strictly ascending versions").isGreaterThan(versions.get(i - 1));
        }
        assertThatCode(() -> runnerOf(defaults)).doesNotThrowAnyException();
    }

    @Test
    void every_released_migration_is_still_registered_with_the_same_kind_default_and_definition_and_nothing_is_edited_in_place() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Migration m : Migrations.defaults(null, null)) {
            actual.put(m.id(), m.kind() + "|" + m.enabledByDefault() + "|" + Checksums.sha256(m.definition()));
        }
        // append-only: every released id keeps its checksum; a NEW id may only be added at the end of the released list
        RELEASED.forEach((id, fingerprint) -> assertThat(actual).as("released migration " + id
                + " was removed, renamed or EDITED: an applied migration's checksum is stored, so this would be refused as CHECKSUM_MISMATCH "
                + "in every database that has applied it; add a NEW migration instead").containsEntry(id, fingerprint));
        List<String> released = new ArrayList<>(RELEASED.keySet());
        List<String> actualIds = new ArrayList<>(actual.keySet());
        assertThat(actualIds.subList(0, released.size())).isEqualTo(released);
        assertThat(actualIds).as("a new migration was added: append it to RELEASED in this test, with its checksum, in the same PR")
                .hasSameSizeAs(released);
    }
}
