package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.migration.MigrationHistory;
import com.tazzzo.catalog.migration.MigrationLock;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The least-privilege model (DB-4): which MongoDB actions on which collections each identity needs, derived from the
 * application's own collection roster so the role definitions, the startup verifier and the tests cannot drift apart.
 *
 * <p>A {@link Rule} grants actions either DATABASE-WIDE (resource {@code {db, collection: ""}}) or on ONE collection.
 * Grants are compared as {@link Cap capabilities}: a database-wide rule expands to every known collection plus the
 * database-level marker {@link #DB_LEVEL}, so a built-in role such as {@code readWrite} (which includes
 * {@code createIndex}, {@code dropIndex} and {@code dropCollection}) is visibly more than the runtime may hold.
 *
 * <p>Evidence for the sets (see docs/database/DATABASE_STAGING_RUNBOOK.md): outside the {@code migration} and
 * {@code schema} packages the application issues no DDL (a committed test scans for it); a normal start reads
 * {@code listCollections} and the history ({@code schema_migrations}); the jobs' operations are those of the
 * {@code migration} package. The real-database test applies these exact roles and exercises each identity.
 */
public final class PrivilegeModel {

    /** Marker target for database-level actions (and the database-wide expansion). */
    public static final String DB_LEVEL = "";

    /** Actions that are database-level only: a database-wide rule grants them at {@link #DB_LEVEL}, not per collection. */
    static final Set<String> DB_ONLY_ACTIONS = Set.of("listCollections");

    public static final String HISTORY = MigrationHistory.COLLECTION;
    public static final String LOCK = MigrationLock.COLLECTION;

    /** Collections the seed (V0003) and data (V0004) migrations write: insert-if-absent upserts, and the V0004 update. */
    public static final Set<String> SEED_COLLECTIONS =
            Set.of("taxonomy_nodes", "aliases", "attribute_definitions", "attribute_schemas");

    /** The three identities: the application, the migration job, and an optional read-only identity for dry runs. */
    public enum Spec { RUNTIME, MIGRATOR, READER }

    /** {@code collection == null} means database-wide. */
    public record Rule(String collection, Set<String> actions) {
        public boolean dbWide() {
            return collection == null;
        }
    }

    public record Cap(String target, String action) { }

    private PrivilegeModel() {
    }

    public static List<Rule> rules(Spec spec, Collection<String> businessCollections) {
        List<Rule> rules = new ArrayList<>();
        switch (spec) {
            case RUNTIME -> {
                rules.add(new Rule(null, Set.of("listCollections")));
                for (String c : new TreeSet<>(businessCollections)) {
                    rules.add(new Rule(c, Set.of("find", "insert", "update", "remove")));
                }
                rules.add(new Rule(HISTORY, Set.of("find")));
            }
            case READER -> rules.add(new Rule(null, Set.of("listCollections", "listIndexes", "find")));
            case MIGRATOR -> {
                rules.add(new Rule(null, Set.of("listCollections", "listIndexes", "find",
                        "createCollection", "createIndex", "dropIndex", "collMod")));
                rules.add(new Rule(HISTORY, Set.of("insert", "update")));
                rules.add(new Rule(LOCK, Set.of("insert", "update")));
                for (String c : new TreeSet<>(SEED_COLLECTIONS)) {
                    rules.add(new Rule(c, Set.of("insert", "update")));
                }
            }
        }
        return rules;
    }

    /** Every collection the model knows about: the application roster plus the two bookkeeping collections. */
    public static Set<String> universe(Collection<String> businessCollections) {
        Set<String> u = new TreeSet<>(businessCollections);
        u.add(HISTORY);
        u.add(LOCK);
        return u;
    }

    /** Expands rules to capabilities over {@code universe}. */
    public static Set<Cap> expand(List<Rule> rules, Set<String> universe) {
        Set<Cap> caps = new LinkedHashSet<>();
        for (Rule r : rules) {
            for (String a : r.actions()) {
                if (r.dbWide()) {
                    caps.add(new Cap(DB_LEVEL, a));
                    if (!DB_ONLY_ACTIONS.contains(a)) {
                        for (String c : universe) caps.add(new Cap(c, a));
                    }
                } else {
                    caps.add(new Cap(r.collection(), a));
                }
            }
        }
        return caps;
    }

    /** What the profile must hold. */
    public static Set<Cap> required(PrivilegeProfile profile, Collection<String> businessCollections) {
        Set<String> u = universe(businessCollections);
        return switch (profile) {
            case RUNTIME -> expand(rules(Spec.RUNTIME, businessCollections), u);
            case MIGRATION_APPLY -> expand(rules(Spec.MIGRATOR, businessCollections), u);
            case MIGRATION_READ -> expand(rules(Spec.READER, businessCollections), u);
        };
    }

    /** The most the profile may hold: anything beyond it is excess privilege. */
    public static Set<Cap> ceiling(PrivilegeProfile profile, Collection<String> businessCollections) {
        Set<String> u = universe(businessCollections);
        return switch (profile) {
            case RUNTIME -> expand(rules(Spec.RUNTIME, businessCollections), u);
            case MIGRATION_APPLY, MIGRATION_READ -> expand(rules(Spec.MIGRATOR, businessCollections), u);
        };
    }

    /** The role as MongoDB's {@code createRole} command document (also the shape of the committed role files). */
    public static Document createRole(String roleName, Spec spec, String database, Collection<String> businessCollections) {
        List<Document> privileges = new ArrayList<>();
        for (Rule r : rules(spec, businessCollections)) {
            privileges.add(new Document("resource", new Document("db", database)
                    .append("collection", r.dbWide() ? "" : r.collection()))
                    .append("actions", new ArrayList<>(new TreeSet<>(r.actions()))));
        }
        return new Document("createRole", roleName).append("privileges", privileges).append("roles", List.of());
    }
}
