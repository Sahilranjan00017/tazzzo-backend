package com.tazzzo.admin.audit;

import com.tazzzo.common.audit.ActorType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One validated audit-read query. Built ONLY by {@link #parse}: an allowlist of parameter names, each a single non-empty
 * value matching a closed format. There is no free-form query, sort, projection, regex or operator: an unknown name
 * ({@code actorId[$ne]}, {@code sort}, {@code filter}, {@code $where}, ...), a repeated name, or a value outside its format
 * is a 400. Every accepted value is later used only as an EQUALITY or bounded-range operand of a fixed query shape.
 */
public record AuditEventQuery(Optional<ActorType> actorType, Optional<String> actorId, Optional<String> action,
                              Optional<String> targetType, Optional<String> targetId, Optional<String> requestId,
                              Optional<Instant> from, Optional<Instant> to, int limit, Optional<String> cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    static final Set<String> PARAMETERS = Set.of("actorType", "actorId", "action", "targetType", "targetId",
            "requestId", "from", "to", "limit", "cursor");

    // Actor ids as the backend mints them: google:<sub>, service:<role>, system:<name>.
    private static final Pattern ACTOR_ID =
            Pattern.compile("^(google:[A-Za-z0-9_-]{1,255}|service:[a-z0-9-]{1,32}|system:[A-Za-z0-9_.-]{1,64})$");
    private static final Pattern ACTION = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern TARGET_TYPE = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern TARGET_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$");
    // Exactly what RequestIdFilter mints; the backend never trusts an inbound request id.
    public static final Pattern REQUEST_ID = Pattern.compile("^req_[0-9a-f]{20}$");
    // UTC only ('Z'), at most millisecond precision (the ledgers store BSON dates, i.e. milliseconds).
    private static final Pattern UTC_INSTANT =
            Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z$");
    private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
    private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    public AuditEventQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AuditQueryRejected("limit must be between 1 and " + MAX_LIMIT);
        }
        if (targetId.isPresent() && targetType.isEmpty()) {
            throw new AuditQueryRejected("targetId requires targetType");
        }
        if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
            throw new AuditQueryRejected("from must not be after to");
        }
    }

    /** Parses the raw servlet parameter map ({@code name -> values}). */
    public static AuditEventQuery parse(Map<String, String[]> params) {
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            if (!PARAMETERS.contains(e.getKey())) {
                throw new AuditQueryRejected("unsupported query parameter");
            }
            if (e.getValue() == null || e.getValue().length != 1) {
                throw new AuditQueryRejected("query parameter " + e.getKey() + " must appear exactly once");
            }
            if (e.getValue()[0] == null || e.getValue()[0].isEmpty()) {
                throw new AuditQueryRejected("query parameter " + e.getKey() + " must not be empty");
            }
        }
        Optional<ActorType> actorType = value(params, "actorType").map(v -> {
            for (ActorType t : ActorType.values()) {
                if (t.name().equals(v)) {
                    return t;
                }
            }
            throw new AuditQueryRejected("actorType must be HUMAN_ADMIN, SERVICE_ACCOUNT or SYSTEM");
        });
        int limit = value(params, "limit").map(v -> {
            if (!LIMIT.matcher(v).matches()) {
                throw new AuditQueryRejected("limit must be between 1 and " + MAX_LIMIT);
            }
            return Integer.parseInt(v);
        }).orElse(DEFAULT_LIMIT);
        return new AuditEventQuery(actorType,
                matching(params, "actorId", ACTOR_ID),
                matching(params, "action", ACTION),
                matching(params, "targetType", TARGET_TYPE),
                matching(params, "targetId", TARGET_ID),
                matching(params, "requestId", REQUEST_ID),
                instant(params, "from"), instant(params, "to"),
                limit,
                matching(params, "cursor", CURSOR));
    }

    /** The filters only (no limit, no cursor), canonically: a cursor is bound to the filters it was issued for. */
    String filterFingerprintSource() {
        return String.join("\u0000", actorType.map(Enum::name).orElse(""), actorId.orElse(""), action.orElse(""),
                targetType.orElse(""), targetId.orElse(""), requestId.orElse(""),
                from.map(Instant::toString).orElse(""), to.map(Instant::toString).orElse(""));
    }

    private static Optional<String> value(Map<String, String[]> params, String name) {
        String[] v = params.get(name);
        return v == null ? Optional.empty() : Optional.of(v[0]);
    }

    private static Optional<String> matching(Map<String, String[]> params, String name, Pattern format) {
        return value(params, name).map(v -> {
            if (!format.matcher(v).matches()) {
                throw new AuditQueryRejected(name + " has an invalid format");
            }
            return v;
        });
    }

    private static Optional<Instant> instant(Map<String, String[]> params, String name) {
        return value(params, name).map(v -> {
            if (!UTC_INSTANT.matcher(v).matches()) {
                throw new AuditQueryRejected(name + " must be a UTC instant, e.g. 2026-10-03T10:00:00Z");
            }
            try {
                return Instant.parse(v);
            } catch (DateTimeParseException e) {
                throw new AuditQueryRejected(name + " must be a UTC instant, e.g. 2026-10-03T10:00:00Z");
            }
        });
    }
}
