package com.tazzzo.catalog.api;

import com.tazzzo.admin.auth.AdminPrincipalResolver;
import com.tazzzo.common.audit.Actor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The audit {@link Actor} of an INTERNAL admin request: the authenticated {@code AdminPrincipal} (attached by
 * {@link ApiAuthFilter}) plus the server-generated request id from {@link RequestIdFilter} (the same value returned as
 * {@code X-Request-Id}). The identity comes ONLY from authentication: nothing in the request body or headers can choose
 * it. Lives in {@code catalog.api} (the HTTP layer that owns the request id) so {@code admin.auth} depends on no catalog
 * class.
 */
public final class AdminActors {

    private AdminActors() {
    }

    public static Actor require(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID);
        if (!(requestId instanceof String id) || id.isBlank()) {
            throw new IllegalStateException("admin request has no server request id");
        }
        return AdminPrincipalResolver.require(request).toActor(id);
    }
}
