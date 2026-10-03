package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates one or more indexes with a deterministic answer for every pre-existing state:
 * <ul>
 *   <li>absent — create it;</li>
 *   <li>exact definition present — already satisfied, nothing to do;</li>
 *   <li>same keys and options under ANOTHER name — adopt without mutation (never hits IndexOptionsConflict);</li>
 *   <li>conflicting definition — BLOCKED; nothing is dropped or changed automatically.</li>
 * </ul>
 * A unique index may carry a {@link DuplicateCheck}; any duplicate blocks creation and is only reported.
 */
public final class CreateIndexMigration implements Migration {

    private final String id;
    private final String description;
    private final List<IndexSpec> specs;
    private final DuplicateCheck duplicateCheck;

    public CreateIndexMigration(String id, String description, List<IndexSpec> specs, DuplicateCheck duplicateCheck) {
        this.id = id;
        this.description = description;
        this.specs = List.copyOf(specs);
        this.duplicateCheck = duplicateCheck;
    }

    @Override public String id() { return id; }
    @Override public String description() { return description; }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }

    @Override
    public List<String> collections() {
        return specs.stream().map(IndexSpec::collection).distinct().toList();
    }

    @Override
    public String definition() {
        StringBuilder sb = new StringBuilder("create-index");
        for (IndexSpec s : specs) sb.append('\n').append(s.describe());
        if (duplicateCheck != null) sb.append("\npreflight: ").append(duplicateCheck.description());
        return sb.toString();
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        List<String> blockers = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean needsCreate = false;
        for (IndexSpec s : specs) {
            IndexSpec.Inspection in = s.inspect(db);
            switch (in.state()) {
                case ABSENT -> { needsCreate = true; ops.add("create index " + s.describe()); }
                case EXACT -> notes.add("already present: " + s.effectiveName());
                case SAME_KEYS_OTHER_NAME -> notes.add("adopting existing index " + in.existingName()
                        + " (same keys and options) for " + s.effectiveName() + "; no change");
                case CONFLICT -> blockers.add(s.describe() + " — " + in.detail()
                        + "; resolve manually, nothing is dropped automatically");
            }
        }
        if (!blockers.isEmpty()) return Preflight.blocked(blockers);
        if (needsCreate && duplicateCheck != null) {
            List<Document> dups = duplicateCheck.sample(db);
            if (!dups.isEmpty()) {
                return Preflight.blocked(List.of("cannot create a unique index: duplicates exist ("
                        + dups.size() + (dups.size() >= DuplicateCheck.SAMPLE_LIMIT ? "+" : "") + " duplicate group(s); sample "
                        + dups.stream().limit(5).map(Document::toJson).toList()
                        + "). Requirement: " + duplicateCheck.description()
                        + ". Business data is never deleted or merged automatically; remediate and re-run."));
            }
        }
        return needsCreate ? Preflight.ready(ops, notes) : Preflight.satisfied(notes.toArray(String[]::new));
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        List<String> done = new ArrayList<>();
        for (IndexSpec s : specs) {
            IndexSpec.Inspection in = s.inspect(db);
            switch (in.state()) {
                case ABSENT -> { s.create(db); done.add("created " + s.effectiveName()); }
                case EXACT, SAME_KEYS_OTHER_NAME -> done.add("kept " + (in.existingName() == null ? s.effectiveName() : in.existingName()));
                case CONFLICT -> throw new MigrationException(id + ": " + s.describe() + " — " + in.detail());
            }
        }
        return ApplyResult.of(String.join("; ", done));
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        List<String> problems = new ArrayList<>();
        for (IndexSpec s : specs) {
            IndexSpec.State st = s.inspect(db).state();
            if (st != IndexSpec.State.EXACT && st != IndexSpec.State.SAME_KEYS_OTHER_NAME) {
                problems.add("index not in place after apply: " + s.describe() + " (" + st + ")");
            }
        }
        return problems;
    }
}
