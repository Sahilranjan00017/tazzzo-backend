package com.tazzzo.admin.audit;

/**
 * The persisted, insert-only audit ledgers the audit-read API reads. There is deliberately no central audit collection:
 * every audited mutation writes its event, with its {@code actor} subdocument, into its domain's ledger in the SAME
 * transaction as the state change. This enum is the single mapping from a ledger's own field names to the read model.
 *
 * <p>{@code price_events} is NOT a source: rows are purged after rollup (not durable audit), and an attributed price change
 * is already recorded as a {@code PRICE_UPDATED} product event.
 *
 * <p>{@link #rank} is the cross-ledger tie-breaker for identical {@code at}: (at DESC, rank ASC, _id DESC) is a total order.
 */
public enum AuditSource {
    PRODUCT("pe", 0, "product_events", "type", "product", "product_id"),
    TAXONOMY_NODE("ne", 1, "node_events", "event", "taxonomy_node", "node_id"),
    DOMAIN("de", 2, "domain_events", "type", null, "aggregate_id");

    /** Field holding a DOMAIN event's target type ({@code aggregate_type}); product and node targets are fixed. */
    static final String DOMAIN_TARGET_TYPE_FIELD = "aggregate_type";

    final String code;
    final int rank;
    final String collection;
    final String actionField;
    final String fixedTargetType;
    final String targetIdField;

    AuditSource(String code, int rank, String collection, String actionField, String fixedTargetType,
                String targetIdField) {
        this.code = code;
        this.rank = rank;
        this.collection = collection;
        this.actionField = actionField;
        this.fixedTargetType = fixedTargetType;
        this.targetIdField = targetIdField;
    }

    public String collection() {
        return collection;
    }

    /** Can a row of this ledger have target type {@code targetType}? Product and node types are reserved to their ledgers. */
    boolean admitsTargetType(String targetType) {
        if (fixedTargetType != null) {
            return fixedTargetType.equals(targetType);
        }
        return !PRODUCT.fixedTargetType.equals(targetType) && !TAXONOMY_NODE.fixedTargetType.equals(targetType);
    }

    static AuditSource fromCode(String code) {
        for (AuditSource s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new AuditQueryRejected("cursor is malformed");
    }
}
