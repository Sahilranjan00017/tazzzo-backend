package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.datastore.PrivilegeModel.Cap;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the privileges the connected identity ACTUALLY holds (the server's {@code connectionStatus} with
 * {@code showPrivileges: true}) with the {@link PrivilegeProfile} it is meant to run as. Pure: it parses a result
 * document. Findings name actions, collections and resource kinds only: never a user name, credential or host.
 *
 * <p>Findings: {@code NOT_AUTHENTICATED}; {@code OUT_OF_SCOPE_PRIVILEGE} (cluster-wide, any-resource, any-database or
 * another database); {@code EXCESS_PRIVILEGE} (an action beyond the profile's ceiling, including a database-wide
 * grant where the profile allows per-collection grants only, and {@code anyAction}); {@code MISSING_PRIVILEGE}.
 */
public final class PrivilegeInspector {

    /** At most this many collection names are listed per finding (the message stays bounded). */
    private static final int LISTED = 5;

    private PrivilegeInspector() {
    }

    public static List<Violation> inspect(Document connectionStatus, String database,
                                          Collection<String> businessCollections, PrivilegeProfile profile) {
        List<Violation> out = new ArrayList<>();
        Document auth = connectionStatus == null ? null : connectionStatus.get("authInfo", Document.class);
        List<?> users = auth == null ? List.of() : auth.getList("authenticatedUsers", Object.class, List.of());
        if (users.isEmpty()) {
            out.add(new Violation("NOT_AUTHENTICATED",
                    "the connection is not authenticated as a database user, so its privileges cannot be established"));
            return out;
        }
        List<?> privileges = auth.getList("authenticatedUserPrivileges", Object.class, List.of());
        Set<Cap> held = new LinkedHashSet<>();
        Set<String> universe = PrivilegeModel.universe(businessCollections);
        Set<String> outOfScope = new TreeSet<>();
        for (Object p : privileges) {
            Document priv = (Document) p;
            Document resource = priv.get("resource", Document.class);
            List<String> actions = priv.getList("actions", String.class, List.of());
            String scope = scopeOf(resource, database);
            if (scope != null) {
                outOfScope.add(scope + " [" + String.join(",", new TreeSet<>(actions)) + "]");
                continue;
            }
            String collection = resource.getString("collection");
            for (String a : actions) {
                if (collection == null || collection.isEmpty()) {
                    held.add(new Cap(PrivilegeModel.DB_LEVEL, a));
                    if (!PrivilegeModel.DB_ONLY_ACTIONS.contains(a)) {
                        for (String c : universe) held.add(new Cap(c, a));
                    }
                } else {
                    held.add(new Cap(collection, a));
                }
            }
        }
        for (String s : outOfScope) {
            out.add(new Violation("OUT_OF_SCOPE_PRIVILEGE", "privilege outside the application database: " + s));
        }
        Set<Cap> required = PrivilegeModel.required(profile, businessCollections);
        Set<Cap> ceiling = PrivilegeModel.ceiling(profile, businessCollections);

        Set<Cap> missing = new LinkedHashSet<>(required);
        missing.removeAll(held);
        group(missing).forEach((action, targets) -> out.add(new Violation("MISSING_PRIVILEGE",
                action + " is required on " + describe(targets))));

        Set<Cap> excess = new LinkedHashSet<>(held);
        excess.removeAll(ceiling);
        group(excess).forEach((action, targets) -> out.add(new Violation("EXCESS_PRIVILEGE",
                action + " is held on " + describe(targets) + " but is not part of the " + profile + " identity")));
        return out;
    }

    /** null when the resource is a collection or database-wide resource of {@code database}; otherwise a description. */
    private static String scopeOf(Document resource, String database) {
        if (resource == null) return "unknown resource";
        if (Boolean.TRUE.equals(resource.getBoolean("cluster"))) return "cluster-wide";
        if (Boolean.TRUE.equals(resource.getBoolean("anyResource"))) return "any resource";
        String db = resource.getString("db");
        if (db == null) return "unknown resource";
        if (db.isEmpty()) return "all databases";
        if (!db.equals(database)) return "database '" + db + "'";
        return null;
    }

    private static Map<String, Set<String>> group(Set<Cap> caps) {
        Map<String, Set<String>> byAction = new TreeMap<>();
        for (Cap c : caps) {
            byAction.computeIfAbsent(c.action(), k -> new TreeSet<>()).add(c.target().isEmpty() ? "(database-level)" : c.target());
        }
        return byAction;
    }

    private static String describe(Set<String> targets) {
        List<String> l = new ArrayList<>(targets);
        String head = String.join(", ", l.subList(0, Math.min(LISTED, l.size())));
        return l.size() == 1 ? head : l.size() + " collection(s) [" + head + (l.size() > LISTED ? ", ..." : "") + "]";
    }
}
