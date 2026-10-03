package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * V0001 — the baseline schema: every collection and the 48 baseline indexes that {@code SchemaBootstrap}
 * historically created on every start of every instance, now an explicit, locked, recorded step.
 *
 * <p>It ADOPTS an existing database (collections and indexes already present, including an equivalent
 * index under another name) without mutation, creates only what is absent, never fails on
 * IndexOptionsConflict (a conflicting definition BLOCKS instead), and performs the one historical
 * destructive step — dropping the exact superseded pre-PAG-2 products prefix index — only after the
 * superseding index exists.
 */
public final class BaselineSchemaMigration implements Migration {

    private final SchemaBootstrap bootstrap;

    public BaselineSchemaMigration(SchemaBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override public String id() { return "V0001__baseline_schema"; }
    @Override public String description() { return "Collections and baseline indexes (adopts a database created by the legacy bootstrap)"; }
    @Override public MigrationKind kind() { return MigrationKind.SCHEMA; }
    @Override public List<String> collections() { return SchemaBootstrap.COLLECTIONS; }

    @Override
    public String definition() {
        StringBuilder sb = new StringBuilder("baseline collections=").append(String.join(",", SchemaBootstrap.COLLECTIONS));
        for (IndexSpec s : IndexCatalog.BASELINE) sb.append('\n').append(s.describe());
        sb.append("\ndrop superseded products prefix index (exact legacy shape) after PAG-2 exists");
        return sb.toString();
    }

    @Override
    public Preflight preflight(MongoDatabase db) {
        List<String> existing = db.listCollectionNames().into(new ArrayList<>());
        List<String> missing = SchemaBootstrap.COLLECTIONS.stream().filter(c -> !existing.contains(c)).toList();
        List<String> blockers = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int create = 0, adopt = 0, exact = 0;
        for (IndexSpec s : IndexCatalog.BASELINE) {
            IndexSpec.Inspection in = s.inspect(db);
            switch (in.state()) {
                case ABSENT -> create++;
                case EXACT -> exact++;
                case SAME_KEYS_OTHER_NAME -> { adopt++; notes.add("adopting " + in.existingName() + " for " + s.describe()); }
                case CONFLICT -> blockers.add(s.describe() + " — " + in.detail());
            }
        }
        if (!blockers.isEmpty()) return Preflight.blocked(blockers);
        boolean legacyIndex = bootstrap.hasSupersededProductsPrefixIndex(db);
        if (!missing.isEmpty()) ops.add("create " + missing.size() + " collection(s): " + missing);
        if (create > 0) ops.add("create " + create + " index(es) (" + exact + " already exact, " + adopt + " adopted)");
        if (legacyIndex) ops.add("drop superseded products prefix index (exact legacy shape; PAG-2 index is created first)");
        if (existing.contains("products")) {
            Document info = db.listCollections().filter(com.mongodb.client.model.Filters.eq("name", "products")).first();
            Document opts = info == null ? null : info.get("options", Document.class);
            notes.add("products validator: " + (opts != null && opts.get("validator") != null ? "PRESENT" : "ABSENT")
                    + " (not modified by the baseline; see the validator migration mechanism)");
        }
        return ops.isEmpty() ? Preflight.satisfied(notes.toArray(String[]::new)) : Preflight.ready(ops, notes);
    }

    @Override
    public ApplyResult apply(MongoDatabase db) {
        bootstrap.ensureCollections(db);
        int created = 0;
        for (IndexSpec s : IndexCatalog.BASELINE) {
            IndexSpec.Inspection in = s.inspect(db);
            switch (in.state()) {
                case ABSENT -> { s.create(db); created++; }
                case EXACT, SAME_KEYS_OTHER_NAME -> { }
                case CONFLICT -> throw new MigrationException(id() + ": " + s.describe() + " — " + in.detail());
            }
        }
        boolean dropped = bootstrap.hasSupersededProductsPrefixIndex(db);
        if (dropped) bootstrap.dropSupersededProductsPrefixIndex(db);
        return ApplyResult.of("collections ensured; " + created + " index(es) created" + (dropped ? "; superseded products prefix index dropped" : ""));
    }

    @Override
    public List<String> validate(MongoDatabase db) {
        List<String> problems = new ArrayList<>();
        List<String> existing = db.listCollectionNames().into(new ArrayList<>());
        for (String c : SchemaBootstrap.COLLECTIONS) {
            if (!existing.contains(c)) problems.add("collection missing after apply: " + c);
        }
        for (IndexSpec s : IndexCatalog.BASELINE) {
            IndexSpec.State st = s.inspect(db).state();
            if (st != IndexSpec.State.EXACT && st != IndexSpec.State.SAME_KEYS_OTHER_NAME) {
                problems.add("baseline index missing after apply: " + s.describe() + " (" + st + ")");
            }
        }
        if (bootstrap.hasSupersededProductsPrefixIndex(db)) problems.add("superseded products prefix index still present");
        return problems;
    }
}
