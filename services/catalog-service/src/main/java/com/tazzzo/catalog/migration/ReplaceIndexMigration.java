package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces one index with another (a redefinition, or a pure rename).
 * <ul>
 *   <li>A redefinition (different keys or options) is CREATE-BEFORE-DROP: the new index exists before the old
 *       one goes, so there is no window without coverage.</li>
 *   <li>A pure rename (same keys and options, new name) cannot be create-before-drop, because MongoDB forbids
 *       two identical indexes under different names. It is drop-then-create and there is a short window without
 *       the index; the runbook requires scheduling it under the migration lock in a quiet period.</li>
 * </ul>
 * The old index is dropped only if it is exactly the reviewed definition.
 */
public final class ReplaceIndexMigration implements Migration {

    private final String id;
    private final String description;
    private final IndexSpec from;
    private final IndexSpec to;
    private final boolean enabledByDefault;

    public ReplaceIndexMigration(String id, String description, IndexSpec from, IndexSpec to, boolean enabledByDefault) {
        if (!from.collection().equals(to.collection())) throw new IllegalArgumentException("same collection required");
        this.id = id;
        this.description = description;
        this.from = from;
        this.to = to;
        this.enabledByDefault = enabledByDefault;
    }

    private boolean pureRename() {
        return from.sameDefinitionIgnoringName(to);
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }
    @Override public List<String> collections() { return List.of(from.collection()); }
    @Override public boolean enabledByDefault() { return enabledByDefault; }

    @Override
    public String definition() {
        return (pureRename() ? "rename-index " : "replace-index ") + from.describe() + " -> " + to.describe();
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        IndexSpec.Inspection oldIn = from.inspect(db);
        if (pureRename()) {
            // for a pure rename the 'to' spec sees the old index as SAME_KEYS_OTHER_NAME; interpret via 'from'
            IndexSpec.Inspection newIn = to.inspect(db);
            if (newIn.state() == IndexSpec.State.EXACT && !from.namedIndexPresent(db)) {
                return Preflight.satisfied("already renamed to " + to.effectiveName());
            }
            if (newIn.state() == IndexSpec.State.CONFLICT) {
                return Preflight.blocked(List.of("rename target " + to.describe() + " — " + newIn.detail()
                        + "; nothing is dropped"));
            }
            if (oldIn.state() == IndexSpec.State.EXACT) {
                return Preflight.ready(List.of("drop index " + from.effectiveName(), "create index " + to.describe()),
                        List.of("pure rename: the index is absent between the drop and the create"));
            }
            return Preflight.blocked(List.of("rename source " + from.describe() + " is not present as the exact reviewed definition ("
                    + oldIn.state() + "); target is " + newIn.state()));
        }
        IndexSpec.Inspection newIn = to.inspect(db);
        if (newIn.state() == IndexSpec.State.CONFLICT) {
            return Preflight.blocked(List.of(to.describe() + " — " + newIn.detail()));
        }
        if (oldIn.state() == IndexSpec.State.CONFLICT || oldIn.state() == IndexSpec.State.SAME_KEYS_OTHER_NAME) {
            return Preflight.blocked(List.of("index being replaced is not the exact reviewed definition: " + from.describe()
                    + " — " + oldIn.detail()));
        }
        boolean newOk = newIn.state() == IndexSpec.State.EXACT || newIn.state() == IndexSpec.State.SAME_KEYS_OTHER_NAME;
        if (newOk && oldIn.state() == IndexSpec.State.ABSENT) return Preflight.satisfied("replacement already in place");
        List<String> ops = new ArrayList<>();
        if (!newOk) ops.add("create index " + to.describe());
        if (oldIn.state() == IndexSpec.State.EXACT) ops.add("drop index " + from.effectiveName());
        return Preflight.ready(ops, List.of("create-before-drop: no window without coverage"));
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        List<String> done = new ArrayList<>();
        if (pureRename()) {
            if (from.inspect(db).state() == IndexSpec.State.EXACT) {
                db.getCollection(from.collection()).dropIndex(from.effectiveName());
                done.add("dropped " + from.effectiveName());
            }
            if (!to.namedIndexPresent(db)) {
                to.create(db);
                done.add("created " + to.effectiveName());
            }
        } else {
            IndexSpec.State ns = to.inspect(db).state();
            if (ns == IndexSpec.State.ABSENT) { to.create(db); done.add("created " + to.effectiveName()); }
            else if (ns == IndexSpec.State.CONFLICT) throw new MigrationException(id + ": " + to.describe() + " conflicts");
            if (from.inspect(db).state() == IndexSpec.State.EXACT) {
                db.getCollection(from.collection()).dropIndex(from.effectiveName());
                done.add("dropped " + from.effectiveName());
            }
        }
        return new ApplyResult(String.join("; ", done), new Document("restore", from.describe()));
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        List<String> problems = new ArrayList<>();
        IndexSpec.State ns = to.inspect(db).state();
        if (ns != IndexSpec.State.EXACT && ns != IndexSpec.State.SAME_KEYS_OTHER_NAME) problems.add("replacement missing: " + to.describe());
        if (!pureRename() && from.inspect(db).state() != IndexSpec.State.ABSENT) problems.add("old index still present: " + from.describe());
        return problems;
    }
}
