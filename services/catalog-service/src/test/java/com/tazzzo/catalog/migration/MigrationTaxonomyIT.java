package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Reference initialization (V0003) and the explicit R3 data migration (V0004). */
class MigrationTaxonomyIT extends AbstractMigrationIT {

    private static final String SEED = "V0003__taxonomy_seed_0_9_0";
    private static final String DATA = "V0004__seed_schemas_pack_fields_not_required";

    private MongoDatabase legacyDatabase() {
        MongoDatabase d = scratch();
        schemaBootstrap.bootstrap(d);
        return d;
    }

    private MigrationRunner only(MongoDatabase d, String... ids) {
        Set<String> wanted = Set.of(ids);
        return runner(d, Migrations.defaults(schemaBootstrap, taxonomyLoader).stream().filter(m -> wanted.contains(m.id())).toList());
    }

    private static boolean required(Document schema, String key) {
        for (Document f : schema.getList("fields", Document.class)) {
            if (key.equals(f.getString("key"))) return Boolean.TRUE.equals(f.getBoolean("required"));
        }
        throw new AssertionError("no field " + key);
    }

    private static Document schema(MongoDatabase d, String id, int version) {
        return d.getCollection("attribute_schemas").find(Filters.and(Filters.eq("schema_id", id), Filters.eq("version", version))).first();
    }

    // ---- V0003 ---------------------------------------------------------------------------------

    @Test
    void the_seed_is_inserted_if_absent_and_never_modifies_existing_documents() {
        MongoDatabase d = legacyDatabase();
        MigrationRunner r = only(d, SEED);
        assertThat(r.apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(taxonomyLoader.seedStatus(d).complete()).isTrue();
        assertThat(d.getCollection("taxonomy_nodes").countDocuments()).isEqualTo(460);

        // partial loss: three nodes missing, and one existing node has since been changed through normal operations
        List<String> ids = d.getCollection("taxonomy_nodes").find().limit(4).map(x -> x.getString("_id")).into(new ArrayList<>());
        d.getCollection("taxonomy_nodes").deleteMany(Filters.in("_id", ids.subList(0, 3)));
        d.getCollection("taxonomy_nodes").updateOne(Filters.eq("_id", ids.get(3)), new Document("$set", new Document("name", "RENAMED BY AN ADMIN")));
        d.getCollection(MigrationHistory.COLLECTION).deleteOne(new Document("_id", SEED)); // re-evaluate from live state

        MigrationRunner.RunReport dry = r.dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(dry.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.WOULD_APPLY);
        assertThat(dry.steps().get(0).operations().get(0)).contains("3 node(s)");

        assertThat(r.apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(d.getCollection("taxonomy_nodes").countDocuments()).isEqualTo(460);
        assertThat(d.getCollection("taxonomy_nodes").find(Filters.eq("_id", ids.get(3))).first().getString("name"))
                .as("insert-if-absent never overwrites").isEqualTo("RENAMED BY AN ADMIN");
    }

    // ---- V0004 ---------------------------------------------------------------------------------

    @Test
    void the_data_migration_needs_approval_and_touches_only_seed_schemas_at_version_1() {
        MongoDatabase d = legacyDatabase();
        taxonomyLoader.load(d);
        Document seeded = d.getCollection("attribute_schemas").find(Filters.and(Filters.eq("version", 1), Filters.eq("fields.key", "pack_size"))).first();
        String schemaId = seeded.getString("schema_id");

        // 1) a seed v1 schema persisted by an OLDER loader (pack fields still required)
        d.getCollection("attribute_schemas").updateOne(Filters.and(Filters.eq("schema_id", schemaId), Filters.eq("version", 1)),
                new Document("$set", new Document("fields.$[q].required", true)),
                new UpdateOptions().arrayFilters(List.of(new Document("q.key", new Document("$in", List.of("pack_size", "pack_unit"))))));
        // 2) a later, legitimately authored version that requires pack_size
        List<Document> v2Fields = new ArrayList<>();
        for (Document f : seeded.getList("fields", Document.class)) {
            Document c = new Document(f);
            if ("pack_size".equals(f.getString("key"))) c.put("required", true);
            v2Fields.add(c);
        }
        d.getCollection("attribute_schemas").insertOne(new Document("schema_id", schemaId).append("version", 2)
                .append("status", "active").append("fields", v2Fields));
        // 3) a non-seed schema that also requires pack_size
        d.getCollection("attribute_schemas").insertOne(new Document("schema_id", "custom_schema").append("version", 1)
                .append("status", "active").append("fields", List.of(new Document("key", "pack_size").append("required", true))));
        MigrationRunner r = only(d, DATA);

        MigrationRunner.RunReport dry = r.dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(dry.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.PENDING_APPROVAL);
        assertThat(dry.steps().get(0).operations().get(0)).contains("seed schema document(s), version 1 only");

        MigrationRunner.RunReport unapproved = r.apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(unapproved.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.PENDING_APPROVAL);
        assertThat(required(schema(d, schemaId, 1), "pack_size")).as("nothing changes without approval").isTrue();
        assertThat(history(d, DATA)).isNull();

        MigrationRunner.RunReport approved = r.apply(target(d), MigrationRunner.Selection.all().withApproved(Set.of(DATA)), apply());
        assertThat(approved.ok()).as(approved.render()).isTrue();
        assertThat(approved.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.APPLIED_NOW);

        assertThat(required(schema(d, schemaId, 1), "pack_size")).as("seed v1 corrected").isFalse();
        assertThat(required(schema(d, schemaId, 2), "pack_size")).as("a later authored version is NEVER touched").isTrue();
        assertThat(required(schema(d, "custom_schema", 1), "pack_size")).as("a non-seed schema is NEVER touched").isTrue();
        assertThat(history(d, DATA).getString("note")).contains("version 1 only");
        assertThat(r.apply(target(d), MigrationRunner.Selection.all().withApproved(Set.of(DATA)), apply()).steps().get(0).status())
                .isEqualTo(MigrationRunner.StepStatus.ALREADY_APPLIED);
    }

    @Test
    void a_database_that_already_has_the_ratified_shape_adopts_without_any_approval() {
        MongoDatabase d = legacyDatabase();
        taxonomyLoader.load(d); // the loader inserts the ratified shape
        MigrationRunner.RunReport r = only(d, DATA).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(r.steps().get(0).status()).isEqualTo(MigrationRunner.StepStatus.ADOPTED);
    }

    @Test
    void the_data_migration_definition_names_exactly_what_it_does() {
        Migration data = Migrations.defaults(schemaBootstrap, taxonomyLoader).stream().filter(m -> m.id().equals(DATA)).findFirst().orElseThrow();
        assertThat(data.kind()).isEqualTo(MigrationKind.DATA);
        assertThat(data.requiresApproval()).isTrue();
        assertThat(data.definition()).contains("version 1 ONLY").contains("later versions untouched").contains("forward-only")
                .contains("pack_size").contains("pack_unit");
        assertThat(TaxonomyLoader.PACK_FIELD_KEYS).containsExactlyInAnyOrder("pack_size", "pack_unit");
    }
}
