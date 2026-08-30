package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads the FROZEN taxonomy v0.9.0 release artifact (generated from the canonical master
 * CSV — the release pipeline in miniature) into taxonomy_nodes, aliases,
 * attribute_definitions and attribute_schemas. Idempotent: replaces by natural key, never
 * duplicates. This is the seed step of Implementation Contract §10.
 */
@Component
public class TaxonomyLoader {

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
                    new Document("$setOnInsert", new Document(sc).append("status", "active")),
                    new UpdateOptions().upsert(true));
            schemas++;
        }
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
