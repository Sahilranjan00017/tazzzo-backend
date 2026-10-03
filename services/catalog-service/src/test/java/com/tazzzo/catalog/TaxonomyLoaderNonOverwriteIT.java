package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3 regression: normal startup must not continuously rewrite persisted attribute schemas.
 *
 * <p>CHARACTERIZATION of the behaviour BEFORE the fix (DB-3): {@code TaxonomyLoader.load} ended with an
 * unconditional {@code updateMany} that set {@code required=false} for {@code pack_size}/{@code pack_unit}
 * on EVERY matching {@code attribute_schemas} document, any version, on every start. A later, already
 * authored version was therefore silently rewritten by a restart.
 */
class TaxonomyLoaderNonOverwriteIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader loader;

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

    @Test
    void characterization_a_reload_currently_rewrites_a_later_authored_schema_version() {
        db.getCollection("attribute_schemas").drop();
        db.getCollection("attribute_schemas").createIndex(new Document("schema_id", 1).append("version", 1),
                new com.mongodb.client.model.IndexOptions().unique(true));
        loader.load(db);

        Document seeded = db.getCollection("attribute_schemas").find(
                Filters.and(Filters.eq("version", 1), Filters.eq("fields.key", "pack_size"))).first();
        assertThat(seeded).as("a seed schema that carries pack_size").isNotNull();
        String schemaId = seeded.getString("schema_id");

        // an already-authored later version that legitimately requires pack_size
        List<Document> v2Fields = new ArrayList<>();
        for (Document f : seeded.getList("fields", Document.class)) {
            Document copy = new Document(f);
            if ("pack_size".equals(f.getString("key"))) copy.put("required", true);
            v2Fields.add(copy);
        }
        db.getCollection("attribute_schemas").insertOne(new Document("schema_id", schemaId).append("version", 2)
                .append("scope", seeded.getString("scope")).append("status", "active").append("fields", v2Fields));
        assertThat(required(schema(schemaId, 2), "pack_size")).as("precondition: v2 requires pack_size").isTrue();

        loader.load(db); // what every service restart does

        // CURRENT (pre-fix) behaviour: the restart overwrote the persisted later version.
        assertThat(required(schema(schemaId, 2), "pack_size"))
                .as("R3 defect: v2 was rewritten to required=false by a plain reload").isFalse();
    }
}
