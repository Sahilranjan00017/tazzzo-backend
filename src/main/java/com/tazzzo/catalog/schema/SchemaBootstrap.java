package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates all collections + validators + indexes idempotently.
 * The products validator is the Java rendering of docs/contract_attack.js — the validator
 * that was executed and verified against MongoDB 7 (26/26). Semantics must not drift.
 * Registered language list [en, hi] and the empty ext {} registry are the generated parts.
 */
@Component
public class SchemaBootstrap {

    public static final List<String> COLLECTIONS = List.of(
            "products", "gtin_registry", "identity_keys", "brands",
            "product_events", "classification_history", "evidence", "evidence_links",
            "work_queue", "offers_current", "catalogue_releases",
            "batches", "campaigns", "campaign_membership", "aliases", "variant_groups",
            "marketplace_crosswalks", "system_config", "attachment_registry",
            "price_events", "price_rollups", "rollup_state",
            "taxonomy_nodes", "attribute_definitions", "attribute_schemas",
            "node_events", "taxonomy_snapshot_nodes", "id_sequences");

    public void bootstrap(MongoDatabase db) {
        List<String> existing = db.listCollectionNames().into(new java.util.ArrayList<>());

        if (!existing.contains("products")) {
            db.createCollection("products", new CreateCollectionOptions().validationOptions(
                    new ValidationOptions()
                            .validator(new Document("$jsonSchema", productsSchema()))
                            .validationLevel(ValidationLevel.STRICT)
                            .validationAction(ValidationAction.ERROR)));
        }
        for (String name : COLLECTIONS) {
            if (!name.equals("products") && !existing.contains(name)) {
                db.createCollection(name);
            }
        }

        db.getCollection("products").createIndex(
                Indexes.ascending("classification.vertical_id", "lifecycle", "classification.status"));
        db.getCollection("products").createIndex(
                Indexes.ascending("bundle_contents.component_product_id"), new IndexOptions().sparse(true));
        db.getCollection("products").createIndex(
                Indexes.ascending("variant_group_id"), new IndexOptions().sparse(true));
        db.getCollection("offers_current").createIndex(
                Indexes.ascending("product_id", "source", "seller", "channel"), new IndexOptions().unique(true));
        db.getCollection("evidence_links").createIndex(Indexes.ascending("evidence_id", "active"));
        db.getCollection("evidence_links").createIndex(Indexes.ascending("product_id", "link_type"));
        db.getCollection("classification_history").createIndex(
                Indexes.ascending("product_id", "decided_at"));
        db.getCollection("product_events").createIndex(Indexes.ascending("product_id", "at"));
        db.getCollection("work_queue").createIndex(Indexes.ascending("status", "type"));
        db.getCollection("batches").createIndex(
                Indexes.ascending("product_id", "lot_no"), new IndexOptions().unique(true));
        db.getCollection("campaign_membership").createIndex(
                Indexes.ascending("campaign_id", "product_id"), new IndexOptions().unique(true));
        db.getCollection("aliases").createIndex(
                Indexes.ascending("alias_norm", "lang", "region"), new IndexOptions().unique(true));
        db.getCollection("price_events").createIndex(Indexes.ascending("product_id", "ts"));
        db.getCollection("price_events").createIndex(Indexes.ascending("rolled", "ts"));
        db.getCollection("taxonomy_nodes").createIndex(Indexes.ascending("parent_id"));
        db.getCollection("taxonomy_nodes").createIndex(Indexes.ascending("node_type", "status"));
        db.getCollection("attribute_definitions").createIndex(
                Indexes.ascending("key", "version"), new IndexOptions().unique(true));
        db.getCollection("attribute_schemas").createIndex(
                Indexes.ascending("schema_id", "version"), new IndexOptions().unique(true));
        db.getCollection("node_events").createIndex(Indexes.ascending("node_id", "at"));
        db.getCollection("taxonomy_snapshot_nodes").createIndex(
                Indexes.ascending("release_id", "node_id"), new IndexOptions().unique(true));
        // Freeze semantics: at most ONE release may be open (publishing OR freezing) at a
        // time — enforced on the constant `gate` marker, cleared only on activation.
        db.getCollection("catalogue_releases").createIndex(Indexes.ascending("gate"),
                new IndexOptions().unique(true).partialFilterExpression(
                        new Document("gate", "OPEN")));
        db.getCollection("price_rollups").createIndex(
                Indexes.ascending("product_id", "seller"), new IndexOptions().unique(true));
    }

    static Document productsSchema() {
        String json = """
        { "bsonType": "object", "additionalProperties": false,
          "required": ["_id","product_type","identity","brand_code","title","lifecycle",
                       "classification","attributes","attributes_meta","version","created_at"],
          "properties": {
            "_id": {"bsonType":"string","pattern":"^TZP-"},
            "product_type": {"enum":["single","variant_pack","bundle"]},
            "lifecycle": {"enum":["draft","active","merging","discontinued","archived","merged"]},
            "identity": {"bsonType":"object","additionalProperties":false,"required":["type"],
              "properties":{"type":{"enum":["gtin","internal"]},"internal_key":{"bsonType":["string","null"]}}},
            "gtins": {"bsonType":"array","maxItems":12,"items":{"bsonType":"object","additionalProperties":false,
              "required":["value"],"properties":{"value":{"bsonType":"string"},"market":{"bsonType":"string"},
              "valid_from":{"bsonType":"date"},"valid_to":{"bsonType":["date","null"]}}}},
            "brand_code": {"bsonType":"string"},
            "title": {"bsonType":"string"},
            "localized_titles": {"bsonType":"object","additionalProperties":false,
              "properties":{"en":{"bsonType":"string"},"hi":{"bsonType":"string"}}},
            "classification": {"bsonType":"object","additionalProperties":false,
              "required":["vertical_id","release_id","status"],
              "properties":{"vertical_id":{"bsonType":["string","null"]},
                "release_id":{"bsonType":"string"},
                "status":{"enum":["confirmed","provisional","review","scope_blocked"]},
                "confidence":{"bsonType":["double","null"],"minimum":0,"maximum":1},
                "method_detail":{"bsonType":"object"},
                "evidence_refs":{"bsonType":"array","maxItems":20,"items":{"bsonType":"string","pattern":"^EV-"}}}},
            "attributes": {"bsonType":"object"},
            "attributes_meta": {"bsonType":"object","required":["validated_release"],
              "properties":{"validated_release":{"bsonType":"string"}}},
            "attribute_provenance": {"bsonType":"object"},
            "bundle_contents": {"bsonType":["array","null"],"maxItems":100,"items":{"bsonType":"object",
              "additionalProperties":false,"required":["component_product_id","qty"],
              "properties":{"component_product_id":{"bsonType":"string","pattern":"^TZP-"},
                "qty":{"bsonType":"int","minimum":1},"vertical_id_snapshot":{"bsonType":"string"},
                "title_snapshot":{"bsonType":"string"},"gtin_snapshot":{"bsonType":["string","null"]}}}},
            "pack_of": {"bsonType":["object","null"],"additionalProperties":false,
              "required":["component_product_id","qty"],
              "properties":{"component_product_id":{"bsonType":"string","pattern":"^TZP-"},
                "qty":{"bsonType":"int","minimum":2}}},
            "browse_verticals": {"bsonType":["array","null"],"maxItems":120,"items":{"bsonType":"string"}},
            "variant_group_id": {"bsonType":["string","null"]},
            "formulation_version": {"bsonType":["int","null"]},
            "ext": {"bsonType":"object","additionalProperties":false,"properties":{}},
            "merged_into": {"bsonType":["string","null"]},
            "version": {"bsonType":"int","minimum":1},
            "created_at": {"bsonType":"date"},
            "updated_at": {"bsonType":["date","null"]}},
          "oneOf": [
            { "properties": { "product_type": {"enum":["single"]},
                "classification": {"properties":{"vertical_id":{"bsonType":"string"}}},
                "bundle_contents": {"bsonType":"null"},
                "pack_of": {"bsonType":"null"} } },
            { "required": ["pack_of"],
              "properties": { "product_type": {"enum":["variant_pack"]},
                "classification": {"properties":{"vertical_id":{"bsonType":"string"}}},
                "bundle_contents": {"bsonType":"null"},
                "pack_of": {"bsonType":"object"} } },
            { "properties": { "product_type": {"enum":["bundle"]},
                "classification": {"properties":{"vertical_id":{"bsonType":"null"}}},
                "bundle_contents": {"bsonType":"array","minItems":2},
                "pack_of": {"bsonType":"null"} } } ] }
        """;
        return Document.parse(json);
    }
}
