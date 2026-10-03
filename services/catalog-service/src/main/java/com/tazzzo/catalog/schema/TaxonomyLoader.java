package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Loads the FROZEN taxonomy v0.9.0 release artifact (generated from the canonical master
 * CSV — the release pipeline in miniature) into taxonomy_nodes, aliases,
 * attribute_definitions and attribute_schemas. INSERT-IF-ABSENT only: it never replaces,
 * rewrites or clobbers an existing document, so it is safe to run repeatedly (R3). This is the
 * seed step of Implementation Contract §10. Evolution of already-persisted reference data is NOT
 * done here; it is an explicit, versioned migration (see {@code catalog.migration}).
 */
@Component
public class TaxonomyLoader {

    /** U-4-f: attribute keys that are never required to catalogue a SKU. */
    public static final Set<String> PACK_FIELD_KEYS = Set.of("pack_size", "pack_unit");

    /**
     * U-4-f (RATIFIED 2026-09-01) applied to a SEED schema document: a stated commercial quantity is not
     * required to catalogue a SKU, so {@code pack_size}/{@code pack_unit} are never required. Returns a
     * copy; the frozen seed artifact and persisted documents are never touched. Used for fresh seeding
     * (in memory, at insert time) and by the explicit data migration for databases seeded earlier.
     */
    public static Document withRatifiedPackRule(Document schema) {
        Document copy = new Document(schema);
        List<Document> fields = new ArrayList<>();
        for (Document f : schema.getList("fields", Document.class)) {
            Document fc = new Document(f);
            if (PACK_FIELD_KEYS.contains(f.getString("key"))) fc.put("required", false);
            fields.add(fc);
        }
        copy.put("fields", fields);
        return copy;
    }

    /** The schema ids carried by the frozen seed artifact (all are seeded at version 1). */
    public List<String> seedSchemaIds() {
        List<String> ids = new ArrayList<>();
        for (Document sc : readSeed().getList("attribute_schemas", Document.class)) {
            ids.add(sc.getString("schema_id"));
        }
        return ids;
    }

    /** How much of the frozen seed is still absent (read-only; creates nothing). */
    public record SeedStatus(int nodesMissing, int aliasesMissing, int definitionsMissing, int schemasMissing) {
        public int totalMissing() {
            return nodesMissing + aliasesMissing + definitionsMissing + schemasMissing;
        }

        public boolean complete() {
            return totalMissing() == 0;
        }
    }

    /** Counts seed documents that are not yet present, by natural key. Strictly read-only. */
    public SeedStatus seedStatus(MongoDatabase db) {
        Document seed = readSeed();
        List<Document> nodes = seed.getList("nodes", Document.class);
        List<String> ids = new ArrayList<>();
        for (Document n : nodes) ids.add(n.getString("_id"));
        long nodesPresent = db.getCollection("taxonomy_nodes").countDocuments(Filters.in("_id", ids));
        int aliasesMissing = 0;
        for (Document a : seed.getList("aliases", Document.class)) {
            if (db.getCollection("aliases").countDocuments(Filters.and(
                    Filters.eq("alias_norm", a.getString("alias_norm")), Filters.eq("lang", a.getString("lang")),
                    Filters.eq("region", a.getString("region")))) == 0) aliasesMissing++;
        }
        int defsMissing = 0;
        for (Document d : seed.getList("attribute_definitions", Document.class)) {
            if (db.getCollection("attribute_definitions").countDocuments(Filters.and(
                    Filters.eq("key", d.getString("key")), Filters.eq("version", d.getInteger("version")))) == 0) defsMissing++;
        }
        int schemasMissing = 0;
        for (Document sc : seed.getList("attribute_schemas", Document.class)) {
            if (db.getCollection("attribute_schemas").countDocuments(Filters.and(
                    Filters.eq("schema_id", sc.getString("schema_id")), Filters.eq("version", sc.getInteger("version")))) == 0) schemasMissing++;
        }
        return new SeedStatus((int) (nodes.size() - nodesPresent), aliasesMissing, defsMissing, schemasMissing);
    }

    public LoadResult load(MongoDatabase db) {
        Document seed = readSeed();
        int nodes = 0, aliases = 0, defs = 0, schemas = 0;
        for (Document n : seed.getList("nodes", Document.class)) {
            // insert-if-absent ONLY: the loader seeds fresh databases and must never clobber
            // nodes that have since been changed through the change machinery.
            Document onInsert = new Document(n).append("version", 1);
            db.getCollection("taxonomy_nodes").updateOne(
                    Filters.eq("_id", n.getString("_id")),
                    new Document("$setOnInsert", onInsert),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
            nodes++;
        }
        for (Document a : seed.getList("aliases", Document.class)) {
            // insert-if-absent: a reload must never revert alias re-pointing done by merges
            db.getCollection("aliases").updateOne(
                    Filters.and(Filters.eq("alias_norm", a.getString("alias_norm")),
                            Filters.eq("lang", a.getString("lang")),
                            Filters.eq("region", a.getString("region"))),
                    new Document("$setOnInsert", a), new UpdateOptions().upsert(true));
            aliases++;
        }
        for (Document d : seed.getList("attribute_definitions", Document.class)) {
            db.getCollection("attribute_definitions").updateOne(
                    Filters.and(Filters.eq("key", d.getString("key")),
                            Filters.eq("version", d.getInteger("version"))),
                    new Document("$setOnInsert", new Document(d).append("status", "active")),
                    new UpdateOptions().upsert(true));
            defs++;
        }
        for (Document sc : seed.getList("attribute_schemas", Document.class)) {
            db.getCollection("attribute_schemas").updateOne(
                    Filters.and(Filters.eq("schema_id", sc.getString("schema_id")),
                            Filters.eq("version", sc.getInteger("version"))),
                    // R3: the ratified U-4-f shape is applied to the in-memory seed copy that is inserted
                    // if absent. A persisted schema document is never rewritten by a load.
                    new Document("$setOnInsert", withRatifiedPackRule(sc).append("status", "active")),
                    new UpdateOptions().upsert(true));
            schemas++;
        }
        // R3: there is deliberately NO rewrite of persisted attribute schemas here. U-4-f is applied above to
        // the seed copy at insert time (fresh databases). Databases seeded before that, if any, are corrected
        // by the explicit, approval-gated, versioned data migration (V0004), never by a service restart.

        return new LoadResult(nodes, aliases, defs, schemas);
    }

    private Document readSeed() {
        try (InputStream in = getClass().getResourceAsStream("/taxonomy_v0_9_0_seed.json")) {
            if (in == null) throw new IllegalStateException("taxonomy seed resource missing");
            return Document.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read taxonomy seed", e);
        }
    }

    public record LoadResult(int nodes, int aliases, int definitions, int schemas) { }
}
