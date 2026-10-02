package com.tazzzo.admin.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * The single typed seam between the admin authentication filter and the code that needs the caller (mirrors
 * {@code CustomerPrincipalResolver}). Business code never reads raw request attributes. There is no anonymous admin: a
 * missing principal is an empty result ({@link #current}) or a failure ({@link #require}), never a default identity; a
 * value of the wrong type under the attribute is a programming defect and fails loud.
 */
public final class AdminPrincipalResolver {

    static final String ATTRIBUTE = "com.tazzzo.admin.auth.admin_principal";

    private AdminPrincipalResolver() {
    }

    /** Called ONLY by the admin authentication filter after a successful authentication. */
    public static void attach(HttpServletRequest request, AdminPrincipal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("principal required");
        }
        request.setAttribute(ATTRIBUTE, principal);
    }

    public static Optional<AdminPrincipal> current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof AdminPrincipal p) {
            return Optional.of(p);
        }
        throw new IllegalStateException("admin principal attribute holds an unexpected type");
    }

    public static AdminPrincipal require(HttpServletRequest request) {
        return current(request).orElseThrow(() -> new IllegalStateException("no authenticated admin principal"));
    }
}
