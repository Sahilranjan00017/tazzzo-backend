package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;

/**
 * V0004 — DATA migration (needs explicit approval): corrects databases that were seeded BEFORE the ratified
 * U-4-f shape was applied at insert time, by setting {@code pack_size}/{@code pack_unit} required=false on the
 * SEED schemas at version 1 only.
 *
 * <p>This is the explicit, versioned replacement for the restart-time rewrite R3 removed. It is deliberately
 * narrow: only the seed schema ids, only {@code version: 1}; later authored versions are never touched.
 * Forward-only (the prior value is not restored automatically); the preflight reports how many documents
 * would change so the operator can decide.
 */
public final class TaxonomyPackFieldsDataMigration implements Migration {

    private final TaxonomyLoader loader;

    public TaxonomyPackFieldsDataMigration(TaxonomyLoader loader) {
        this.loader = loader;
    }

    @Override public String id() { return "V0004__seed_schemas_pack_fields_not_required"; }
    @Override public String description() { return "U-4-f: pack_size/pack_unit not required on seed attribute schemas at version 1 (data migration, approval required)"; }
    @Override public MigrationKind kind() { return MigrationKind.DATA; }
    @Override public List<String> collections() { return List.of("attribute_schemas"); }

    @Override
    public String definition() {
        return "attribute_schemas: for seed schema ids, version 1 ONLY, set fields.required=false where key in "
                + TaxonomyLoader.PACK_FIELD_KEYS.stream().sorted().toList() + "; later versions untouched; forward-only";
    }

    private Bson stillRequired(List<String> seedIds) {
        return Filters.and(Filters.in("schema_id", seedIds), Filters.eq("version", 1),
                Filters.elemMatch("fields", Filters.and(Filters.in("key", TaxonomyLoader.PACK_FIELD_KEYS),
                        Filters.ne("required", false))));
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        long n = db.getCollection("attribute_schemas").countDocuments(stillRequired(loader.seedSchemaIds()));
        if (n == 0) return Preflight.satisfied("no seed v1 schema requires pack_size/pack_unit");
        return Preflight.ready(List.of("set required=false for pack fields on " + n + " seed schema document(s), version 1 only"),
                List.of("later authored versions are not touched", "forward-only data change; requires approval"));
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        List<String> seedIds = loader.seedSchemaIds();
        long modified = db.getCollection("attribute_schemas").updateMany(
                Filters.and(Filters.in("schema_id", seedIds), Filters.eq("version", 1)),
                new Document("$set", new Document("fields.$[q].required", false)),
                new UpdateOptions().arrayFilters(List.of(new Document("q.key",
                        new Document("$in", List.copyOf(TaxonomyLoader.PACK_FIELD_KEYS)))))).getModifiedCount();
        return ApplyResult.of("modified " + modified + " seed schema document(s), version 1 only");
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        long left = db.getCollection("attribute_schemas").countDocuments(stillRequired(loader.seedSchemaIds()));
        return left == 0 ? List.of() : List.of(left + " seed v1 schema(s) still require pack fields after apply");
    }
}
