package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.List;

/**
 * Drops one reviewed index, and ONLY if the live index is exactly that reviewed definition. A same-keys
 * index under another name, or any different definition, blocks: this migration never drops something it
 * was not shown. Roll-forward to undo: re-create the index from the same {@link IndexSpec}.
 */
public final class DropIndexMigration implements Migration {

    private final String id;
    private final String description;
    private final IndexSpec spec;
    private final boolean enabledByDefault;

    public DropIndexMigration(String id, String description, IndexSpec spec, boolean enabledByDefault) {
        this.id = id;
        this.description = description;
        this.spec = spec;
        this.enabledByDefault = enabledByDefault;
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }
    @Override public List<String> collections() { return List.of(spec.collection()); }
    @Override public boolean enabledByDefault() { return enabledByDefault; }
    @Override public String definition() { return "drop-index " + spec.describe(); }

    @Override
    public Preflight preflight(MongoDatabase db) {
        IndexSpec.Inspection in = spec.inspect(db);
        return switch (in.state()) {
            case ABSENT -> Preflight.satisfied("index already absent: " + spec.effectiveName());
            case EXACT -> Preflight.ready(List.of("drop index " + spec.describe()),
                    List.of("roll-forward: re-create from the same definition"));
            case SAME_KEYS_OTHER_NAME -> Preflight.blocked(List.of("an index with the reviewed keys exists under the name "
                    + in.existingName() + ", not " + spec.effectiveName() + "; refusing to drop an index that is not the exact reviewed definition"));
            case CONFLICT -> Preflight.blocked(List.of(spec.describe() + " — " + in.detail()
                    + "; refusing to drop a different definition"));
        };
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        IndexSpec.Inspection in = spec.inspect(db);
        switch (in.state()) {
            case ABSENT -> { return ApplyResult.of("already absent"); }
            case EXACT -> {
                db.getCollection(spec.collection()).dropIndex(spec.effectiveName());
                return new ApplyResult("dropped " + spec.effectiveName(),
                        new Document("recreate", spec.describe()));
            }
            default -> throw new MigrationException(id + ": refusing to drop " + spec.describe() + " — " + in.detail());
        }
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        return spec.inspect(db).state() == IndexSpec.State.ABSENT
                ? List.of() : List.of("index still present after drop: " + spec.describe());
    }
}
