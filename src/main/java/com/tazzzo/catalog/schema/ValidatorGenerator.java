package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Release-pipeline validator generation (M6/Law 4b): the products validator's language keys
 * and ext keys are GENERATED from system_config / attachment_registry — never hand-edited.
 * regenerate() = the collMod step of T6; the release flips to active only after this + drift
 * check succeed.
 */
@Component
public class ValidatorGenerator {

    public Document generateProductsSchema(MongoDatabase db) {
        Document schema = SchemaBootstrap.productsSchema();
        Document props = schema.get("properties", Document.class);

        // languages from system_config (config_type=languages) — additive growth path G-4
        Document langCfg = db.getCollection("system_config")
                .find(new Document("config_type", "languages")).first();
        List<String> langs = (langCfg == null || langCfg.getList("values", String.class) == null)
                ? List.of("en", "hi") : langCfg.getList("values", String.class);
        Document langProps = new Document();
        for (String l : langs) langProps.append(l, new Document("bsonType", "string"));
        props.put("localized_titles", new Document("bsonType", "object")
                .append("additionalProperties", false).append("properties", langProps));

        // ext keys from attachment_registry (Law 4b closed world)
        Document extProps = new Document();
        List<Document> types = db.getCollection("attachment_registry")
                .find(new Document("target", "products.ext")).into(new ArrayList<>());
        for (Document t : types) extProps.append(t.getString("attachment_type"), new Document("bsonType", "object"));
        props.put("ext", new Document("bsonType", "object")
                .append("additionalProperties", false).append("properties", extProps));
        return schema;
    }

    /** The collMod step: apply the generated validator to the live collection. */
    public void regenerate(MongoDatabase db) {
        db.runCommand(new Document("collMod", "products")
                .append("validator", new Document("$jsonSchema", generateProductsSchema(db)))
                .append("validationLevel", "strict")
                .append("validationAction", "error"));
    }
}
