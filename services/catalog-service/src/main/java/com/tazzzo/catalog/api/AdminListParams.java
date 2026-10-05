package com.tazzzo.catalog.api;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The closed grammar of an admin list query: only the allowlisted names, each at most once and never empty, values from a
 * safe token alphabet, {@code limit} 1..200 (default 50). Anything else is refused ({@link IllegalArgumentException}, which
 * the API maps to 400) rather than ignored, so a typo can never widen a query.
 */
final class AdminListParams {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private final Map<String, String> values;
    private final int limit;

    private AdminListParams(Map<String, String> values, int limit) {
        this.values = values;
        this.limit = limit;
    }

    static AdminListParams read(HttpServletRequest request, Set<String> allowed) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            String name = e.getKey();
            if (!allowed.contains(name) && !name.equals("limit") && !name.equals("cursor")) {
                throw new IllegalArgumentException("unsupported query parameter");
            }
            if (e.getValue().length != 1) {
                throw new IllegalArgumentException("query parameter " + name + " must appear exactly once");
            }
            String v = e.getValue()[0];
            if (!TOKEN.matcher(v).matches()) {
                throw new IllegalArgumentException("query parameter " + name + " is empty or malformed");
            }
            out.put(name, v);
        }
        int limit = DEFAULT_LIMIT;
        String raw = out.get("limit");
        if (raw != null) {
            if (!raw.matches("[1-9][0-9]{0,2}") || Integer.parseInt(raw) > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            limit = Integer.parseInt(raw);
        }
        return new AdminListParams(out, limit);
    }

    String get(String name) {
        return values.get(name);
    }

    String cursor() {
        return values.get("cursor");
    }

    int limit() {
        return limit;
    }
}
