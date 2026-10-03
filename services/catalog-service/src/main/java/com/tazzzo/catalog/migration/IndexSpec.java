package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Declarative definition of one index: the single description used to inspect, create, adopt, drop and
 * verify it. {@code name == null} means MongoDB's generated default name derived from the key pattern.
 */
public record IndexSpec(String collection, String name, LinkedHashMap<String, Integer> keys, boolean unique,
                        boolean sparse, Document partialFilter, Long ttlSeconds) {

    /** How the live database relates to this spec. */
    public enum State {
        /** No index with this name or key pattern (or the collection does not exist). */
        ABSENT,
        /** Same name and same definition. */
        EXACT,
        /** Same keys and same options under a DIFFERENT name: the need is met; adopt without mutation. */
        SAME_KEYS_OTHER_NAME,
        /** Name taken by a different definition, or same keys with different options. Never auto-resolved. */
        CONFLICT
    }

    public record Inspection(State state, String existingName, String detail) { }

    public String effectiveName() {
        if (name != null) return name;
        StringBuilder sb = new StringBuilder();
        keys.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "_").append(k).append('_').append(v));
        return sb.toString();
    }

    public Document keyDocument() {
        Document d = new Document();
        keys.forEach(d::append);
        return d;
    }

    public IndexOptions options() {
        IndexOptions o = new IndexOptions().name(effectiveName());
        if (unique) o.unique(true);
        if (sparse) o.sparse(true);
        if (partialFilter != null) o.partialFilterExpression(partialFilter);
        if (ttlSeconds != null) o.expireAfter(ttlSeconds, TimeUnit.SECONDS);
        return o;
    }

    public void create(MongoDatabase db) {
        db.getCollection(collection).createIndex(keyDocument(), options());
    }

    /**
     * Key patterns are ORDER-SENSITIVE: {@code {a:1,b:1}} and {@code {b:1,a:1}} are different indexes (they serve
     * different queries). {@code LinkedHashMap.equals} ignores order, so compare the entry LISTS.
     */
    static boolean sameKeyPattern(LinkedHashMap<String, Integer> a, LinkedHashMap<String, Integer> b) {
        return new java.util.ArrayList<>(a.entrySet()).equals(new java.util.ArrayList<>(b.entrySet()));
    }

    /**
     * Two index definitions can coexist on one collection only if their key patterns differ, or both are partial
     * with DIFFERENT filters (as {@code otp_one_delivering_per_phone} and {@code otp_one_active_per_phone}).
     * MongoDB forbids two indexes with the same keys and options under different names.
     */
    public boolean canCoexistWith(IndexSpec other) {
        if (!sameKeyPattern(keys, other.keys)) return true;
        return partialFilter != null && other.partialFilter != null && !Objects.equals(partialFilter, other.partialFilter);
    }

    /** Same keys (in order) and options, ignoring only the name (a pure rename). */
    public boolean sameDefinitionIgnoringName(IndexSpec other) {
        return collection.equals(other.collection) && sameKeyPattern(keys, other.keys) && unique == other.unique
                && sparse == other.sparse && Objects.equals(partialFilter, other.partialFilter)
                && Objects.equals(ttlSeconds, other.ttlSeconds);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(collection).append('.').append(effectiveName()).append(" {");
        keys.forEach((k, v) -> sb.append(k).append(v > 0 ? " asc" : " desc").append(", "));
        sb.setLength(sb.length() - 2);
        sb.append('}');
        if (unique) sb.append(" unique");
        if (sparse) sb.append(" sparse");
        if (partialFilter != null) sb.append(" partial ").append(partialFilter.toJson());
        if (ttlSeconds != null) sb.append(" ttl ").append(ttlSeconds).append('s');
        return sb.toString();
    }

    /** True when an index with exactly this NAME exists (regardless of its definition). */
    public boolean namedIndexPresent(MongoDatabase db) {
        for (String c : db.listCollectionNames()) {
            if (c.equals(collection)) {
                for (Document actual : db.getCollection(collection).listIndexes()) {
                    if (effectiveName().equals(actual.getString("name"))) return true;
                }
                return false;
            }
        }
        return false;
    }

    public Inspection inspect(MongoDatabase db) {
        return inspect(db, null);
    }

    /** As {@link #inspect(MongoDatabase)} but pretends the index named {@code ignoreName} does not exist. */
    public Inspection inspect(MongoDatabase db, String ignoreName) {
        boolean exists = false;
        for (String c : db.listCollectionNames()) {
            if (c.equals(collection)) { exists = true; break; }
        }
        if (!exists) return new Inspection(State.ABSENT, null, "collection does not exist");
        String wanted = effectiveName();
        Inspection sameKeys = null;
        for (Document actual : db.getCollection(collection).listIndexes()) {
            String actualName = actual.getString("name");
            if (ignoreName != null && ignoreName.equals(actualName)) continue;
            boolean keysEqual = sameKeyPattern(keysOf(actual), keys);
            if (actualName.equals(wanted)) {
                return keysEqual && optionsEqual(actual)
                        ? new Inspection(State.EXACT, actualName, "exact definition present")
                        : new Inspection(State.CONFLICT, actualName,
                                "index named " + wanted + " exists with a different definition");
            }
            if (keysEqual) {
                if (optionsEqual(actual)) {
                    sameKeys = new Inspection(State.SAME_KEYS_OTHER_NAME, actualName,
                            "same keys and options exist under the name " + actualName);
                } else if (!(actual.get("partialFilterExpression") != null && partialFilter != null
                        && !Objects.equals(actual.get("partialFilterExpression"), partialFilter))) {
                    // differing options on the same key pattern cannot coexist; two DIFFERENT partial filters can
                    return new Inspection(State.CONFLICT, actualName,
                            "an index with the same keys but different options exists under the name " + actualName);
                }
            }
        }
        return sameKeys != null ? sameKeys : new Inspection(State.ABSENT, null, "no index with this name or key pattern");
    }

    private boolean optionsEqual(Document actual) {
        Number ttl = (Number) actual.get("expireAfterSeconds");
        return Boolean.TRUE.equals(actual.getBoolean("unique")) == unique
                && Boolean.TRUE.equals(actual.getBoolean("sparse")) == sparse
                && Objects.equals(actual.get("partialFilterExpression"), partialFilter)
                && Objects.equals(ttl == null ? null : ttl.longValue(), ttlSeconds)
                && actual.get("collation") == null && actual.get("hidden") == null;
    }

    private static LinkedHashMap<String, Integer> keysOf(Document index) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        Document key = index.get("key", Document.class);
        key.forEach((f, d) -> m.put(f, d instanceof Number n ? n.intValue() : Integer.MIN_VALUE));
        return m;
    }
}
