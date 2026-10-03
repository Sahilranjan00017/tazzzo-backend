package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3 regression: normal startup must not continuously rewrite persisted attribute schemas.
 *
 * <p>Before DB-3, {@code TaxonomyLoader.load} ended with an unconditional {@code updateMany} that set
 * {@code required=false} for {@code pack_size}/{@code pack_unit} on EVERY matching {@code attribute_schemas}
 * document, any version, on every start (pinned by the previous commit's characterization test). Now the
 * ratified U-4-f shape is applied to the seed copy at INSERT time only; a reload never rewrites a persisted
 * document. Existing databases are corrected by the explicit, approval-gated data migration (see
 * {@code MigrationTaxonomyIT}).
 */
class TaxonomyLoaderNonOverwriteIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader loader;

    @BeforeEach
    void freshSchemas() {
        db.getCollection("attribute_schemas").drop();
        db.getCollection("attribute_schemas").createIndex(new Document("schema_id", 1).append("version", 1),
                new com.mongodb.client.model.IndexOptions().unique(true));
    }

    private static boolean required(Document schema, String field) {
        for (Document f : schema.getList("fields", Document.class)) {
            if (field.equals(f.getString("key"))) return Boolean.TRUE.equals(f.getBoolean("required"));
        }
        throw new AssertionError("field " + field + " not in schema " + schema.getString("schema_id"));
    }

    private Document schema(String schemaId, int version) {
        return db.getCollection("attribute_schemas").find(
                Filters.and(Filters.eq("schema_id", schemaId), Filters.eq("version", version))).first();
    }

    private Document seededSchemaWithPackSize() {
        Document d = db.getCollection("attribute_schemas").find(
                Filters.and(Filters.eq("version", 1), Filters.eq("fields.key", "pack_size"))).first();
        assertThat(d).as("a seed schema that carries pack_size").isNotNull();
        return d;
    }

    @Test
    void a_reload_preserves_a_later_authored_schema_version() {
        loader.load(db);
        Document seeded = seededSchemaWithPackSize();
        String schemaId = seeded.getString("schema_id");

        List<Document> v2Fields = new ArrayList<>();
        for (Document f : seeded.getList("fields", Document.class)) {
            Document copy = new Document(f);
            if ("pack_size".equals(f.getString("key"))) copy.put("required", true);
            v2Fields.add(copy);
        }
        db.getCollection("attribute_schemas").insertOne(new Document("schema_id", schemaId).append("version", 2)
                .append("scope", seeded.getString("scope")).append("status", "active").append("fields", v2Fields));
        assertThat(required(schema(schemaId, 2), "pack_size")).as("precondition").isTrue();

        loader.load(db); // what every service restart does
        loader.load(db);

        assertThat(required(schema(schemaId, 2), "pack_size"))
                .as("R3: a restart must not rewrite an already-authored later version").isTrue();
    }

    @Test
    void a_reload_does_not_rewrite_any_persisted_seed_document() {
        loader.load(db);
        Document seeded = seededSchemaWithPackSize();
        String schemaId = seeded.getString("schema_id");
        // simulate a persisted v1 that differs from the seed shape (e.g. seeded by an older loader)
        db.getCollection("attribute_schemas").updateOne(
                Filters.and(Filters.eq("schema_id", schemaId), Filters.eq("version", 1)),
                new Document("$set", new Document("fields.$[q].required", true)),
                new UpdateOptions().arrayFilters(List.of(new Document("q.key", "pack_size"))));
        assertThat(required(schema(schemaId, 1), "pack_size")).as("precondition").isTrue();

        loader.load(db);

        assertThat(required(schema(schemaId, 1), "pack_size"))
                .as("R3: a load is insert-if-absent; it never rewrites a persisted document").isTrue();
    }

    @Test
    void a_fresh_seed_carries_the_ratified_pack_shape_and_nothing_else_changes() {
        TaxonomyLoader.LoadResult r = loader.load(db);
        assertThat(r.schemas()).isEqualTo(48);
        assertThat(db.getCollection("attribute_schemas").countDocuments()).isEqualTo(48);
        long packFields = 0;
        for (Document sc : db.getCollection("attribute_schemas").find()) {
            for (Document f : sc.getList("fields", Document.class)) {
                if (TaxonomyLoader.PACK_FIELD_KEYS.contains(f.getString("key"))) {
                    packFields++;
                    assertThat(f.getBoolean("required")).as("U-4-f: %s.%s", sc.getString("schema_id"), f.getString("key"))
                            .isFalse();
                }
            }
        }
        assertThat(packFields).as("seed schemas carrying pack fields").isGreaterThan(0);
    }

    @Test
    void the_ratified_rule_touches_only_the_pack_fields_and_returns_a_copy() {
        Document original = new Document("schema_id", "S").append("version", 1).append("fields", List.of(
                new Document("key", "pack_size").append("required", true),
                new Document("key", "pack_unit").append("required", true),
                new Document("key", "fat_pct").append("required", true)));
        Document out = TaxonomyLoader.withRatifiedPackRule(original);
        assertThat(required(out, "pack_size")).isFalse();
        assertThat(required(out, "pack_unit")).isFalse();
        assertThat(required(out, "fat_pct")).isTrue();
        assertThat(required(original, "pack_size")).as("input is not mutated").isTrue();
    }

    @Test
    void loading_twice_is_idempotent() {
        loader.load(db);
        long nodes = db.getCollection("taxonomy_nodes").countDocuments();
        long defs = db.getCollection("attribute_definitions").countDocuments();
        long schemas = db.getCollection("attribute_schemas").countDocuments();
        loader.load(db);
        assertThat(db.getCollection("taxonomy_nodes").countDocuments()).isEqualTo(nodes);
        assertThat(db.getCollection("attribute_definitions").countDocuments()).isEqualTo(defs);
        assertThat(db.getCollection("attribute_schemas").countDocuments()).isEqualTo(schemas);
    }
}
