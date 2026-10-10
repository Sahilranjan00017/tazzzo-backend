package com.tazzzo.catalog.api.docs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DOCUMENTATION-ONLY. The status codes of every {@code /v1/**} operation, as published in the hand-written app contract
 * {@code docs/api/v1/openapi.yaml}. The generated spec reports the same set per operation so the two files cannot tell
 * different stories; {@code OpenApiContractIT} fails the build when this table and the YAML disagree (in either
 * direction), and {@code ApiContractParityIT} already pins the YAML's operations to the served routes.
 *
 * <p>Keys are {@code METHOD path} with every path variable normalised to {@code {}}.
 */
final class V1StatusCatalog {

    private V1StatusCatalog() {
    }

    private static final Map<String, List<Integer>> STATUSES = new HashMap<>();

    static {
        String[] rows = {
            "GET /v1/app-config", "200,400,406,429,503",
            "POST /v1/auth/logout", "204,401",
            "POST /v1/auth/otp/request", "202,400,406,413,415,429,503",
            "POST /v1/auth/otp/verify", "200,400,406,413,415,429,503",
            "POST /v1/auth/refresh", "200,400,401,406,413,415,503",
            "POST /v1/auth/session", "200,400,401,406,413,415,503",
            "GET /v1/categories", "200,404,406,429,503",
            "GET /v1/categories/{}", "200,400,404,406,429,503",
            "GET /v1/categories/{}/children", "200,404,406,429,503",
            "GET /v1/categories/{}/products", "200,400,404,406,429,503",
            "GET /v1/content/faqs", "200,400,406,429,503",
            "GET /v1/content/home", "200,400,406,429,503",
            "POST /v1/customer/account/deletion", "200,400,401,406,413,415,429,500,503",
            "GET /v1/customer/addresses", "200,401,406,429,503",
            "POST /v1/customer/addresses", "201,400,401,406,409,413,415,429,503",
            "DELETE /v1/customer/addresses/{}", "204,401,404,412,428,429,503",
            "GET /v1/customer/addresses/{}", "200,401,404,406,429,503",
            "PATCH /v1/customer/addresses/{}", "200,400,401,404,406,412,413,415,428,429,503",
            "PUT /v1/customer/addresses/{}/default", "200,401,404,406,429,503",
            "DELETE /v1/customer/cart", "200,400,401,404,406,412,428,429,503",
            "GET /v1/customer/cart", "200,401,404,406,429,503",
            "DELETE /v1/customer/cart/items/{}", "200,400,401,404,406,412,428,429,503",
            "PUT /v1/customer/cart/items/{}", "200,400,401,404,406,409,412,413,415,428,429,503",
            "POST /v1/customer/checkout/quote", "200,400,401,404,406,409,410,412,413,415,428,429,503",
            "GET /v1/customer/checkout/quotes/{}", "200,401,404,406,410,429,503",
            "GET /v1/customer/delivery/slots", "200,400,401,406,429,503",
            "GET /v1/customer/orders", "200,400,401,406,429,503",
            "POST /v1/customer/orders", "200,400,401,404,406,409,410,413,415,429,500,503",
            "GET /v1/customer/orders/{}", "200,401,404,406,429,500,503",
            "POST /v1/customer/orders/{}/cancel", "200,400,401,404,406,409,413,415,429,500,503",
            "GET /v1/customer/profile", "200,401,406,429,503",
            "PATCH /v1/customer/profile", "200,400,401,406,412,413,415,428,429,503",
            "GET /v1/customer/support/cases", "200,400,401,404,406,409,429,503",
            "POST /v1/customer/support/cases", "201,400,401,404,406,409,413,415,429,503",
            "GET /v1/customer/support/cases/{}", "200,400,401,404,406,409,429,503",
            "POST /v1/customer/support/cases/{}/close", "200,400,401,404,406,409,429,503",
            "POST /v1/customer/support/cases/{}/messages", "200,400,401,404,406,409,413,415,429,503",
            "GET /v1/products/{}", "200,400,404,406,429,503",
            "GET /v1/products:batch", "200,400,406,429,503",
            "GET /v1/search", "200,400,406,429,503",
            "GET /v1/serviceability", "200,400,406,429,503",
            // legacy mobile namespace, generated spec only (not part of the YAML contract)
            "GET /catalog/v1/categories", "200,400,404,429,503",
            "GET /catalog/v1/categories/{}/children", "200,400,404,429,503",
            "GET /catalog/v1/categories/{}/products", "200,400,404,429,503",
            "GET /catalog/v1/products/{}", "200,400,404,429,503",
            // probes
            "GET /health/live", "200",
            "GET /health/ready", "200,503",
        };
        for (int i = 0; i < rows.length; i += 2) {
            STATUSES.put(rows[i], java.util.Arrays.stream(rows[i + 1].split(",")).map(Integer::valueOf).toList());
        }
    }

    static String key(String method, String path) {
        return method.toUpperCase(java.util.Locale.ROOT) + " " + path.replaceAll("\\{[^}]*}", "{}");
    }

    /** The documented statuses of an operation, or {@code null} when the table does not know it. */
    static List<Integer> statuses(String method, String path) {
        return STATUSES.get(key(method, path));
    }

    static java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(STATUSES.keySet());
    }
}
