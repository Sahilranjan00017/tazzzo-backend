package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.schema.TaxonomyLoader;

import java.util.List;

/**
 * V0003 — REFERENCE INITIALIZATION (R3/R5 category C): inserts the frozen taxonomy v0.9.0 seed if absent.
 * Insert-if-absent only: it never replaces or rewrites an existing document, so re-running it is harmless.
 * Evolving already-persisted reference data is a separate, explicit migration (see V0004).
 */
public final class TaxonomySeedMigration implements Migration {

    private final TaxonomyLoader loader;

    public TaxonomySeedMigration(TaxonomyLoader loader) {
        this.loader = loader;
    }

    @Override public String id() { return "V0003__taxonomy_seed_0_9_0"; }
    @Override public String description() { return "Insert-if-absent of the frozen taxonomy v0.9.0 seed (nodes, aliases, definitions, schemas)"; }
    @Override public MigrationKind kind() { return MigrationKind.REFERENCE_INIT; }
    @Override public List<String> collections() { return List.of("taxonomy_nodes", "aliases", "attribute_definitions", "attribute_schemas"); }
    @Override public String definition() { return "insert-if-absent taxonomy_v0_9_0_seed.json (460 nodes, 25 aliases, 110 definitions, 48 schemas); ratified U-4-f shape applied to the in-memory seed copy"; }

    @Override
    public Preflight preflight(MongoDatabase db) {
        TaxonomyLoader.SeedStatus st = loader.seedStatus(db);
        if (st.complete()) return Preflight.satisfied("every seed document is already present");
        return Preflight.ready(List.of("insert-if-absent: " + st.nodesMissing() + " node(s), " + st.aliasesMissing()
                + " alias(es), " + st.definitionsMissing() + " definition(s), " + st.schemasMissing() + " schema(s)"),
                List.of("existing documents are never modified"));
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        TaxonomyLoader.LoadResult r = loader.load(db);
        return ApplyResult.of("seed processed insert-if-absent: " + r.nodes() + " nodes, " + r.aliases() + " aliases, "
                + r.definitions() + " definitions, " + r.schemas() + " schemas");
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        TaxonomyLoader.SeedStatus st = loader.seedStatus(db);
        return st.complete() ? List.of() : List.of(st.totalMissing() + " seed document(s) still absent after apply");
    }
}
