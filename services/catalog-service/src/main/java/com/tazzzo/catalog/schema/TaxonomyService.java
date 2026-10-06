package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Read-side taxonomy resolution: parent walks, path rendering, alias lookup. */
@Component
public class TaxonomyService {

    private final MongoDatabase db;

    public TaxonomyService(MongoDatabase db) {
        this.db = db;
    }

    public Document node(String nodeId) {
        return db.getCollection("taxonomy_nodes").find(Filters.eq("_id", nodeId)).first();
    }

    /**
     * One page of nodes in ascending id order, strictly after {@code afterId}. Each filter is optional; the parent filter
     * is served by the {@code parent_id} index, the type/status pair by {@code (node_type, status)}.
     */
    public List<Document> list(String parentId, String nodeType, String status, String afterId, int limit) {
        List<org.bson.conversions.Bson> filters = new ArrayList<>();
        if (parentId != null) filters.add(Filters.eq("parent_id", parentId));
        if (nodeType != null) filters.add(Filters.eq("node_type", nodeType));
        if (status != null) filters.add(Filters.eq("status", status));
        if (afterId != null) filters.add(Filters.gt("_id", afterId));
        org.bson.conversions.Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        List<Document> out = new ArrayList<>();
        db.getCollection("taxonomy_nodes").find(filter).sort(com.mongodb.client.model.Sorts.ascending("_id"))
                .limit(limit).into(out);
        return out;
    }

    /** Root-first path, e.g. [Staples, Rice & Grains, Basmati Rice, Basmati Rice]. */
    public List<Document> path(String verticalId) {
        List<Document> path = new ArrayList<>();
        Document n = node(verticalId);
        while (n != null) {
            path.add(0, n);
            String parent = n.getString("parent_id");
            n = parent == null ? null : node(parent);
        }
        return path;
    }

    public String renderPath(String verticalId) {
        List<String> names = path(verticalId).stream().map(d -> d.getString("name")).toList();
        return String.join(" > ", names);
    }

    public Document resolveAlias(String aliasNorm) {
        Document a = db.getCollection("aliases")
                .find(Filters.and(Filters.eq("alias_norm", aliasNorm.toLowerCase()),
                        Filters.eq("status", "active"))).first();
        return a == null ? null : node(a.getString("node_id"));
    }
}
