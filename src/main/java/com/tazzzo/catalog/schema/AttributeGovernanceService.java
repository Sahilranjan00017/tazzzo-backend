package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.tx.AttributeViolationException;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE enforcement path between the open products.attributes map (Mongo tier: shape only —
 * proven by bypass test I-6) and the authoritative attribute model
 * (attribute_definitions + attribute_schemas). Service tier of the three-tier invariant
 * table: called by MintService before any product insert.
 *
 * Rules (Canonical Contract 4 + CR-4):
 *  - vertical found  -> STRICT: unknown key rejected; required keys present; type checks;
 *    claim-tier attributes require >=1 evidence ref (A2''' publish gate guards later flips).
 *  - unknown enum VALUE on an enum_open definition -> accepted + attribute_unknown_value
 *    work item (H-11): values grow as data, never as rejections.
 *  - vertical NOT in taxonomy_nodes (pre-release drafts, holding verticals) -> LENIENT:
 *    global-registry checks only + validation_gap work item. Fail-closed to a queue, never
 *    silent (Law 4).
 */
@Component
public class AttributeGovernanceService {

    private final MongoDatabase db;

    public AttributeGovernanceService(MongoDatabase db) {
        this.db = db;
    }

    public List<Document> validate(String verticalId, Map<String, Object> attributes,
                                   List<String> evidenceRefs) {
        List<Document> workItems = new ArrayList<>();
        Document vertical = db.getCollection("taxonomy_nodes")
                .find(Filters.and(Filters.eq("_id", verticalId), Filters.eq("node_type", "vertical")))
                .first();
        String schemaId = vertical == null ? null : vertical.getString("attribute_schema_id");
        Document schema = schemaId == null ? null
                : com.tazzzo.catalog.tx.AttributeAuthoringService.latestActiveIn(
                        db, null, "attribute_schemas", Filters.eq("schema_id", schemaId));

        if (schema != null) {
            List<Document> fields = schema.getList("fields", Document.class);
            for (String key : attributes.keySet()) {
                if (fields.stream().noneMatch(f -> f.getString("key").equals(key))) {
                    throw new AttributeViolationException(
                            "attribute key '" + key + "' is not in schema '" + schemaId + "'");
                }
            }
            for (Document f : fields) {
                if (Boolean.TRUE.equals(f.getBoolean("required"))
                        && !attributes.containsKey(f.getString("key"))) {
                    throw new AttributeViolationException(
                            "required attribute '" + f.getString("key") + "' missing (schema " + schemaId + ")");
                }
            }
        } else if (vertical == null) {
            workItems.add(new Document("_id", "validation_gap:" + verticalId)
                    .append("type", "validation_gap").append("vertical_id", verticalId)
                    .append("status", "pending"));
        }

        // U-4-f: quantity is no longer required for catalogue acceptance, so its absence must
        // not vanish silently. Same shape as validation_gap above — Law 4, fail-closed to a
        // queue. Identity is handled separately: no pack term => no canonical key.
        if (!attributes.containsKey("pack_size") || !attributes.containsKey("pack_unit")) {
            workItems.add(new Document("_id", "attribute_incomplete:" + verticalId)
                    .append("type", "attribute_incomplete").append("vertical_id", verticalId)
                    .append("missing", "pack_size/pack_unit").append("status", "pending"));
        }

        for (Map.Entry<String, Object> e : attributes.entrySet()) {
            Document def = com.tazzzo.catalog.tx.AttributeAuthoringService.latestActiveIn(
                    db, null, "attribute_definitions", Filters.eq("key", e.getKey()));
            if (def == null) continue; // schema check above already governs membership
            String type = def.getString("type");
            Object v = e.getValue();
            boolean ok = switch (type) {
                case "number" -> v instanceof Number;
                case "boolean" -> v instanceof Boolean;
                case "string", "enum_open" -> v instanceof String || v instanceof Number || v instanceof Boolean;
                default -> true;
            };
            if (!ok) throw new AttributeViolationException(
                    "attribute '" + e.getKey() + "' must be " + type);
            if ("enum_open".equals(type) && v instanceof String sv) {
                List<String> known = def.getList("known_values", String.class);
                if (known != null && !known.isEmpty() && !known.contains(sv)) {
                    workItems.add(new Document("_id", "attr_unknown:" + e.getKey() + ":" + sv)
                            .append("type", "attribute_unknown_value").append("key", e.getKey())
                            .append("value", sv).append("status", "pending"));
                }
            }
            if ("claim".equals(def.getString("governance"))
                    && (evidenceRefs == null || evidenceRefs.isEmpty())) {
                throw new AttributeViolationException(
                        "claim-tier attribute '" + e.getKey() + "' requires evidence");
            }
        }
        return workItems;
    }
}
