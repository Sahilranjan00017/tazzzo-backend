package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.domain.ProductIds;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * V0017 — tightens the product-id patterns of the live {@code products} {@code $jsonSchema} validator from the coarse
 * {@code ^TZP-} to the full {@link ProductIds} grammar, on {@code _id}, {@code bundle_contents[].component_product_id},
 * {@code pack_of.component_product_id} and {@code merged_into} (nullable).
 *
 * <p>It PATCHES the live validator (only those four {@code pattern} keys) instead of replacing it with a static schema, so
 * what the release pipeline generated ({@code ValidatorGenerator}: language keys, ext keys) and the validation level/action
 * are preserved. Before any change it scans the collection for documents that would violate the new patterns; if there is
 * one it REFUSES (BLOCKED) with the count and a bounded sample of ids, and changes nothing. Documents are never rewritten.
 */
public final class ProductIdValidatorMigration implements Migration {

    public static final String ID = "V0017__products_validator_product_id_patterns";
    private static final String COLLECTION = "products";
    private static final String PATTERN = ProductIds.MONGO_REGEX;
    public static final int SAMPLE_IDS = 5;
    private static final int MAX_ID_CHARS = 64;

    private final int scanLimit;

    public ProductIdValidatorMigration() { this(100_000); }

    ProductIdValidatorMigration(int scanLimit) { this.scanLimit = scanLimit; }

    @Override public String id() { return ID; }
    @Override public String description() {
        return "products $jsonSchema validator: product-id patterns tightened from ^TZP- to the ProductIds grammar (_id, bundle_contents[].component_product_id, pack_of.component_product_id, merged_into)";
    }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }
    @Override public List<String> collections() { return List.of(COLLECTION); }

    @Override
    public String definition() {
        return "patch validator products: pattern " + PATTERN + " on _id, bundle_contents.items.component_product_id, "
                + "pack_of.component_product_id, merged_into(nullable); keep level/action and every other key; "
                + "refuse (no change) if any existing document violates it; post-verify the live patterns";
    }

    /** The four id-holding properties as a standalone schema; the pre-scan reuses the server's own matcher. */
    static Document scanSchema() {
        Document idString = new Document("bsonType", "string").append("pattern", PATTERN);
        return new Document("bsonType", "object").append("properties", new Document()
                .append("_id", idString)
                .append("bundle_contents", new Document("bsonType", List.of("array", "null"))
                        .append("items", new Document("bsonType", "object").append("properties",
                                new Document("component_product_id", idString))))
                .append("pack_of", new Document("bsonType", List.of("object", "null"))
                        .append("properties", new Document("component_product_id", idString)))
                .append("merged_into", new Document("bsonType", List.of("string", "null")).append("pattern", PATTERN)));
    }

    private static Document violating() {
        return new Document("$nor", List.of(new Document("$jsonSchema", scanSchema())));
    }

    private Document live(MongoDatabase db) {
        Document info = db.listCollections().filter(Filters.eq("name", COLLECTION)).first();
        return info == null ? null : info.get("options", Document.class);
    }

    private static Document schemaOf(Document options) {
        Document v = options == null ? null : options.get("validator", Document.class);
        return v == null ? null : v.get("$jsonSchema", Document.class);
    }

    /** Each of the four pattern slots, or null when the live schema lacks the property. */
    private static List<Document> slots(Document schema) {
        Document props = prop(schema, "properties");
        if (props == null) return null;
        Document id = prop(props, "_id");
        Document bundle = prop(prop(prop(props, "bundle_contents"), "items"), "properties");
        Document pack = prop(prop(props, "pack_of"), "properties");
        Document bundleId = prop(bundle, "component_product_id");
        Document packId = prop(pack, "component_product_id");
        Document merged = prop(props, "merged_into");
        if (id == null || bundleId == null || packId == null || merged == null) return null;
        return List.of(id, bundleId, packId, merged);
    }

    private static Document prop(Document d, String key) {
        return d == null ? null : d.get(key) instanceof Document x ? x : null;
    }

    private static boolean tightened(Document schema) {
        List<Document> s = slots(schema);
        return s != null && s.stream().allMatch(d -> PATTERN.equals(d.getString("pattern")));
    }

    private String unusable(Document options) {
        Document schema = schemaOf(options);
        if (slots(schema) == null) return "the live products validator lacks one of _id, bundle_contents.items.component_product_id, pack_of.component_product_id, merged_into; refusing to guess a repair";
        return null;
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        if (!db.listCollectionNames().into(new ArrayList<>()).contains(COLLECTION)) {
            return Preflight.satisfied("products does not exist yet; V0001 creates it with the tightened validator");
        }
        Document options = live(db);
        if (schemaOf(options) == null) {
            return Preflight.satisfied("products has no $jsonSchema validator, so there is nothing to tighten (V0001 never alters an existing collection)");
        }
        String bad = unusable(options);
        if (bad != null) return Preflight.blocked(List.of(bad));
        if (tightened(schemaOf(options))) return Preflight.satisfied("product-id patterns already tightened on products");
        long n = db.getCollection(COLLECTION).countDocuments(violating(), new CountOptions().limit(scanLimit));
        if (n > 0) {
            List<String> sample = new ArrayList<>();
            db.getCollection(COLLECTION).find(violating()).projection(new Document("_id", 1)).limit(SAMPLE_IDS)
                    .forEach(d -> sample.add(show(d.get("_id"))));
            return Preflight.blocked(List.of(n + (n >= scanLimit ? "+" : "") + " existing products document(s) violate the product-id grammar "
                    + ProductIds.REGEX + " in _id, bundle_contents[].component_product_id, pack_of.component_product_id or merged_into; sample _id "
                    + sample + ". Nothing was changed and documents are never rewritten by a migration: remediate the data, then re-run."));
        }
        return Preflight.ready(List.of("collMod products: product-id patterns -> " + PATTERN + " (level/action unchanged)"),
                List.of("0 existing documents violate the new patterns", "previous options are captured as rollback info"));
    }

    private static String show(Object id) {
        String s = String.valueOf(id).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
        return "'" + (s.length() > MAX_ID_CHARS ? s.substring(0, MAX_ID_CHARS) + "..." : s) + "'";
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        Preflight p = preflight(db);   // re-inspect rather than trust an earlier preflight
        if (p.status() == Preflight.Status.BLOCKED) throw new MigrationException(ID + ": " + String.join("; ", p.blockers()));
        Document options = live(db);
        Document previous = new Document();
        for (String k : List.of("validator", "validationLevel", "validationAction")) {
            if (options != null && options.get(k) != null) previous.put(k, options.get(k));
        }
        if (p.status() == Preflight.Status.ALREADY_SATISFIED) {
            return new ApplyResult(p.notes().get(0), new Document("collection", COLLECTION).append("previous", previous));
        }
        Document patched = Document.parse(schemaOf(options).toJson());
        slots(patched).forEach(d -> d.put("pattern", PATTERN));
        String level = options.getString("validationLevel") == null ? "strict" : options.getString("validationLevel");
        String action = options.getString("validationAction") == null ? "error" : options.getString("validationAction");
        db.runCommand(new Document("collMod", COLLECTION).append("validator", new Document("$jsonSchema", patched))
                .append("validationLevel", level).append("validationAction", action));
        return new ApplyResult("tightened product-id patterns on products", new Document("collection", COLLECTION).append("previous", previous));
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        Document options = live(db);
        if (options == null || schemaOf(options) == null) return List.of();   // nothing to tighten (see preflight)
        if (!tightened(schemaOf(options))) {
            return List.of("live products validator does not carry the tightened product-id patterns after collMod");
        }
        return List.of();
    }
}
