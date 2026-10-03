package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces one index with another (a redefinition or a rename).
 *
 * <p>Whether the two can coexist decides the safe order ({@link IndexSpec#canCoexistWith}):
 * <ul>
 *   <li><b>can coexist</b> (different key pattern, or two different partial filters): CREATE-BEFORE-DROP, so there is
 *       no window without coverage;</li>
 *   <li><b>cannot coexist</b> (same ordered keys, e.g. a pure rename, or a change of options such as adding
 *       {@code unique}): MongoDB forbids two such indexes, so it is DROP-THEN-CREATE and there is a short window
 *       without the index. It is <b>resumable</b>: if the run dies between the two steps, the retry sees the source
 *       gone and the target absent and simply creates the target.</li>
 * </ul>
 * The old index is dropped only if it is exactly the reviewed definition. When the target is unique and was not
 * before, an optional {@link DuplicateCheck} must come back empty before anything is created; duplicates only ever
 * BLOCK, they are never repaired.
 */
public final class ReplaceIndexMigration implements Migration {

    private final String id;
    private final String description;
    private final IndexSpec from;
    private final IndexSpec to;
    private final boolean enabledByDefault;
    private final DuplicateCheck duplicateCheck;

    public ReplaceIndexMigration(String id, String description, IndexSpec from, IndexSpec to, boolean enabledByDefault) {
        this(id, description, from, to, enabledByDefault, null);
    }

    public ReplaceIndexMigration(String id, String description, IndexSpec from, IndexSpec to, boolean enabledByDefault,
                                 DuplicateCheck duplicateCheck) {
        if (!from.collection().equals(to.collection())) throw new IllegalArgumentException("same collection required");
        if (to.unique() && !from.unique() && duplicateCheck == null) {
            // dropping the source and then failing to build the unique target on duplicates would leave the collection
            // without the index; a replacement that makes an index unique must prove there are no duplicates first
            throw new IllegalArgumentException("a replacement that makes an index unique must supply a DuplicateCheck");
        }
        this.id = id;
        this.description = description;
        this.from = from;
        this.to = to;
        this.enabledByDefault = enabledByDefault;
        this.duplicateCheck = duplicateCheck;
    }

    private boolean sameName() {
        return from.effectiveName().equals(to.effectiveName());
    }

    /**
     * True when the old and new definitions cannot exist at the same time, so the old one must go first: the same
     * NAME (an index name is unique per collection, whatever the keys), or the same ordered keys without two
     * different partial filters.
     */
    private boolean dropFirst() {
        return sameName() || !from.canCoexistWith(to);
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }
    @Override public List<String> collections() { return List.of(from.collection()); }
    @Override public boolean enabledByDefault() { return enabledByDefault; }

    @Override
    public String definition() {
        return (dropFirst() ? "replace-index (drop-then-create) " : "replace-index (create-before-drop) ")
                + from.describe() + " -> " + to.describe()
                + (duplicateCheck == null ? "" : "\npreflight: " + duplicateCheck.description());
    }

    private Preflight blockedByDuplicates(MongoDatabase db) {
        if (duplicateCheck == null || !to.unique()) return null;
        List<Document> dups = duplicateCheck.sample(db);
        if (dups.isEmpty()) return null;
        return Preflight.blocked(List.of("cannot create the unique replacement: duplicates exist (" + dups.size()
                + " duplicate group(s); sample " + dups.stream().limit(5).map(Document::toJson).toList()
                + "). Requirement: " + duplicateCheck.description()
                + ". Business data is never deleted or merged automatically; remediate and re-run."));
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        if (sameName()) {
            // The source and the target share one name, so "the index with that name" is judged by its definition.
            IndexSpec.Inspection asTarget = to.inspect(db);
            if (asTarget.state() == IndexSpec.State.EXACT) return Preflight.satisfied("replacement already in place: " + to.effectiveName());
            IndexSpec.Inspection asSource = from.inspect(db);
            if (asSource.state() == IndexSpec.State.EXACT) {
                // Check the target as if the source were already gone: otherwise an equivalent or conflicting index under
                // a THIRD name is hidden behind the same-named source, and apply would drop the source and then fail.
                IndexSpec.Inspection withoutSource = to.inspect(db, from.effectiveName());
                if (withoutSource.state() != IndexSpec.State.ABSENT) {
                    return Preflight.blocked(List.of("cannot replace " + from.effectiveName() + ": " + withoutSource.detail()
                            + " (" + withoutSource.state() + "); nothing is dropped"));
                }
                Preflight dup = blockedByDuplicates(db);
                if (dup != null) return dup;
                return Preflight.ready(List.of("drop index " + from.effectiveName(), "create index " + to.describe()),
                        List.of("same name: drop-then-create, the index is absent between the two steps"));
            }
            if (from.namedIndexPresent(db)) {
                return Preflight.blocked(List.of("the index " + from.effectiveName() + " is neither the exact reviewed source nor the target: "
                        + asSource.detail() + "; nothing is dropped"));
            }
            if (asTarget.state() == IndexSpec.State.ABSENT) {
                Preflight dup = blockedByDuplicates(db);
                if (dup != null) return dup;
                return Preflight.ready(List.of("create index " + to.describe()),
                        List.of("the source is already gone (an interrupted earlier run, or already dropped): resuming with the create"));
            }
            return Preflight.blocked(List.of("cannot create " + to.effectiveName() + ": " + asTarget.detail() + " ("
                    + asTarget.state() + "); resolve it deliberately, nothing is created"));
        }
        if (dropFirst()) {
            IndexSpec.Inspection newIn = to.inspect(db, from.effectiveName()); // the source is about to go; ignore it
            boolean fromPresent = from.namedIndexPresent(db);
            if (newIn.state() == IndexSpec.State.EXACT && !fromPresent) return Preflight.satisfied("replacement already in place: " + to.effectiveName());
            if (newIn.state() == IndexSpec.State.CONFLICT) {
                return Preflight.blocked(List.of("target " + to.describe() + " — " + newIn.detail() + "; nothing is dropped"));
            }
            if (fromPresent) {
                IndexSpec.Inspection oldIn = from.inspect(db);
                if (oldIn.state() != IndexSpec.State.EXACT) {
                    return Preflight.blocked(List.of("the index to replace is not the exact reviewed definition: " + from.describe()
                            + " — " + oldIn.detail()));
                }
                if (newIn.state() != IndexSpec.State.ABSENT) {
                    return Preflight.blocked(List.of("cannot replace " + from.effectiveName() + ": " + newIn.detail()
                            + " (" + newIn.state() + "); nothing is dropped"));
                }
                Preflight dup = blockedByDuplicates(db);
                if (dup != null) return dup;
                return Preflight.ready(List.of("drop index " + from.effectiveName(), "create index " + to.describe()),
                        List.of("same keys cannot coexist: drop-then-create, the index is absent between the two steps"));
            }
            if (newIn.state() == IndexSpec.State.ABSENT) {
                Preflight dup = blockedByDuplicates(db);
                if (dup != null) return dup;
                return Preflight.ready(List.of("create index " + to.describe()),
                        List.of("the source " + from.effectiveName() + " is already gone (an interrupted earlier run, or already dropped): resuming with the create"));
            }
            return Preflight.blocked(List.of("an equivalent index exists under the name " + newIn.existingName()
                    + ", which is neither the source " + from.effectiveName() + " nor the target " + to.effectiveName()));
        }
        IndexSpec.Inspection oldIn = from.inspect(db);
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
        if (!newOk) {
            Preflight dup = blockedByDuplicates(db);
            if (dup != null) return dup;
            ops.add("create index " + to.describe());
        }
        if (oldIn.state() == IndexSpec.State.EXACT) ops.add("drop index " + from.effectiveName());
        return Preflight.ready(ops, List.of("create-before-drop: no window without coverage"));
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        List<String> done = new ArrayList<>();
        if (dropFirst()) {
            if (from.inspect(db).state() == IndexSpec.State.EXACT) {
                db.getCollection(from.collection()).dropIndex(from.effectiveName());
                done.add("dropped " + from.effectiveName());
            }
            IndexSpec.Inspection in = to.inspect(db);
            if (in.state() == IndexSpec.State.ABSENT) {
                to.create(db);
                done.add("created " + to.effectiveName());
            } else if (in.state() != IndexSpec.State.EXACT) {
                // CONFLICT, or an equivalent index appeared under a third name since the preflight: never proceed silently
                throw new MigrationException(id + ": " + to.describe() + " cannot be created — " + in.detail());
            }
        } else {
            IndexSpec.State ns = to.inspect(db).state();
            if (ns == IndexSpec.State.ABSENT) {
                to.create(db);
                done.add("created " + to.effectiveName());
            } else if (ns == IndexSpec.State.CONFLICT) {
                throw new MigrationException(id + ": " + to.describe() + " conflicts");
            }
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
        boolean ok = dropFirst() ? ns == IndexSpec.State.EXACT
                : ns == IndexSpec.State.EXACT || ns == IndexSpec.State.SAME_KEYS_OTHER_NAME;
        if (!ok) problems.add("replacement missing: " + to.describe());
        if (!sameName() && from.namedIndexPresent(db)) problems.add("old index still present: " + from.describe());
        return problems;
    }
}
