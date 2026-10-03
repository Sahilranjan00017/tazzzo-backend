package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Mechanism for applying a server-side {@code $jsonSchema} validator (DB-1 proposed validators; none is
 * registered by DB-3). Lifecycle: conformance scan -> controlled {@code collMod} -> post-validation, with the
 * previous options captured for a manual roll-back and a safe failure path:
 * <ul>
 *   <li>the scan counts existing documents that do NOT conform and reports a bounded sample of ids;</li>
 *   <li>{@code strict + error} with non-conforming documents is BLOCKED (those documents could no longer be
 *       updated); {@code moderate} or {@code warn} tolerates them and says so;</li>
 *   <li>nothing is rewritten, deleted or "fixed" — documents are never touched.</li>
 * </ul>
 */
public final class ValidatorMigration implements Migration {

    public static final int SAMPLE_IDS = 5;

    private final String id;
    private final String description;
    private final String collection;
    private final Document schema;
    private final ValidationLevel level;
    private final ValidationAction action;
    private final boolean enabledByDefault;
    private final int scanLimit;

    public ValidatorMigration(String id, String description, String collection, Document schema,
                              ValidationLevel level, ValidationAction action, boolean enabledByDefault, int scanLimit) {
        this.id = id;
        this.description = description;
        this.collection = collection;
        this.schema = schema;
        this.level = level;
        this.action = action;
        this.enabledByDefault = enabledByDefault;
        this.scanLimit = scanLimit;
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }
    @Override public List<String> collections() { return List.of(collection); }
    @Override public boolean enabledByDefault() { return enabledByDefault; }

    @Override
    public String definition() {
        return "validator " + collection + " level=" + level.getValue() + " action=" + action.getValue()
                + " schema-sha256=" + Checksums.sha256(schema.toJson());
    }

    private boolean collectionExists(MongoDatabase db) {
        for (String c : db.listCollectionNames()) if (c.equals(collection)) return true;
        return false;
    }

    /** Current validator options of the collection: {@code {validator, validationLevel, validationAction}} (nullable parts). */
    public Document currentOptions(MongoDatabase db) {
        Document info = db.listCollections().filter(Filters.eq("name", collection)).first();
        Document options = info == null ? null : info.get("options", Document.class);
        Document out = new Document();
        if (options != null) {
            if (options.get("validator") != null) out.put("validator", options.get("validator"));
            if (options.get("validationLevel") != null) out.put("validationLevel", options.get("validationLevel"));
            if (options.get("validationAction") != null) out.put("validationAction", options.get("validationAction"));
        }
        return out;
    }

    private boolean matchesDesired(Document current) {
        Document validator = current.get("validator", Document.class);
        return validator != null
                && new Document("$jsonSchema", schema).toJson().equals(validator.toJson())
                && level.getValue().equals(current.getString("validationLevel"))
                && action.getValue().equals(current.getString("validationAction"));
    }

    private Document nonConformingFilter() {
        return new Document("$nor", List.of(new Document("$jsonSchema", schema)));
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        if (!collectionExists(db)) {
            return Preflight.blocked(List.of("collection " + collection + " does not exist; create it first"));
        }
        if (matchesDesired(currentOptions(db))) {
            return Preflight.satisfied("validator already in place on " + collection);
        }
        long bad = db.getCollection(collection).countDocuments(nonConformingFilter(), new CountOptions().limit(scanLimit));
        List<String> notes = new ArrayList<>();
        notes.add("previous options are captured as rollback info");
        if (currentOptions(db).get("validator") != null) {
            notes.add("an EXISTING, different validator on " + collection + " will be replaced");
        }
        if (bad > 0) {
            List<Object> sample = new ArrayList<>();
            db.getCollection(collection).find(nonConformingFilter()).projection(new Document("_id", 1))
                    .limit(SAMPLE_IDS).forEach(d -> sample.add(d.get("_id")));
            String detail = bad + (bad >= scanLimit ? "+" : "") + " existing document(s) do not conform; sample _id " + sample;
            if (level == ValidationLevel.STRICT && action == ValidationAction.ERROR) {
                return Preflight.blocked(List.of(detail + ". strict+error would make them unwritable. Remediate the data, or choose "
                        + "moderate/warn deliberately. Documents are never rewritten by a migration."));
            }
            notes.add(detail + " (tolerated by level=" + level.getValue() + " action=" + action.getValue() + ")");
        }
        return Preflight.ready(List.of("collMod " + collection + " validator level=" + level.getValue()
                + " action=" + action.getValue()), notes);
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        if (!collectionExists(db)) throw new MigrationException(id + ": collection " + collection + " does not exist");
        Document previous = currentOptions(db);
        db.runCommand(new Document("collMod", collection)
                .append("validator", new Document("$jsonSchema", schema))
                .append("validationLevel", level.getValue())
                .append("validationAction", action.getValue()));
        return new ApplyResult("applied validator on " + collection,
                new Document("collection", collection).append("previous", previous));
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        return matchesDesired(currentOptions(db)) ? List.of()
                : List.of("validator on " + collection + " does not match the intended definition after collMod");
    }

    /**
     * Manual roll-back: restores the options captured in {@link ApplyResult#rollbackInfo()}. When there was no
     * validator before, it is replaced by an empty one at level off.
     */
    public static void restore(MongoDatabase db, Document rollbackInfo) {
        String coll = rollbackInfo.getString("collection");
        Document previous = rollbackInfo.get("previous", Document.class);
        Document cmd = new Document("collMod", coll);
        if (previous.get("validator") != null) {
            cmd.append("validator", previous.get("validator"))
                    .append("validationLevel", previous.getString("validationLevel") == null ? "strict" : previous.getString("validationLevel"))
                    .append("validationAction", previous.getString("validationAction") == null ? "error" : previous.getString("validationAction"));
        } else {
            cmd.append("validator", new Document()).append("validationLevel", "off");
        }
        db.runCommand(cmd);
    }
}
