package com.tazzzo.admin.auth;

/**
 * The ONE place that decides whether an authenticated admin principal may perform a request on the INTERNAL surface.
 *
 * <p>Two kinds of namespace:
 * <ul>
 *   <li><b>Staff namespaces</b> (customer personal data): {@code /api/v1/admin/orders} requires {@code order-ops}
 *       (read and write) or {@code support-agent} (read only); {@code /api/v1/admin/support} requires {@code support-agent}
 *       (read and write) or {@code order-ops} (read only). Only a per-person {@code HUMAN_ADMIN} can hold a staff role, and
 *       NOTHING else reaches these namespaces: not {@code cms-writer}, not {@code reader}, not {@code audit-reader}, not a
 *       shared service token.</li>
 *   <li><b>Everything else</b> keeps the existing coarse rules: writes need {@code cms-writer}; reads need {@code reader} or
 *       {@code cms-writer}; a principal with neither may GET only the narrow paths (the audit read and {@code /me}). A staff
 *       role confers nothing here except {@code /me}.</li>
 * </ul>
 * Matching is exact on the segment boundary ({@code /api/v1/admin/orders2} is NOT the orders namespace and so falls to the
 * legacy rules); the path is the raw request URI that {@code SurfaceClassifier} has already refused when it carried traversal or
 * encoded structure, so a decorated variant cannot reach a namespace it was not classified into.
 */
public final class AdminAccessPolicy {

    public static final String ORDERS = "/api/v1/admin/orders";
    public static final String SUPPORT = "/api/v1/admin/support";

    public enum Decision { ALLOW, FORBIDDEN_WRITE, FORBIDDEN_READ }

    private AdminAccessPolicy() { }

    public static Decision decide(AdminPrincipal principal, String method, String uri, java.util.Set<String> narrowReadPaths) {
        boolean read = "GET".equals(method);
        if (inNamespace(uri, ORDERS)) {
            return staffDecision(principal, read, AdminPrincipal.ORDER_OPS, AdminPrincipal.SUPPORT_AGENT);
        }
        if (inNamespace(uri, SUPPORT)) {
            return staffDecision(principal, read, AdminPrincipal.SUPPORT_AGENT, AdminPrincipal.ORDER_OPS);
        }
        // legacy coarse rules, unchanged
        if (!read && !principal.canWrite()) {
            return Decision.FORBIDDEN_WRITE;
        }
        boolean narrow = narrowReadPaths.contains(uri);
        if (!principal.canReadCatalog() && !narrow) {
            return Decision.FORBIDDEN_READ;
        }
        return Decision.ALLOW;
    }

    /** {@code owner} may read and write the namespace; {@code reader} may only read it; anyone else is refused. */
    private static Decision staffDecision(AdminPrincipal principal, boolean read, String owner, String readerRole) {
        if (principal.actorType() != com.tazzzo.common.audit.ActorType.HUMAN_ADMIN) {
            return read ? Decision.FORBIDDEN_READ : Decision.FORBIDDEN_WRITE;
        }
        if (principal.hasRole(owner)) {
            return Decision.ALLOW;
        }
        if (principal.hasRole(readerRole)) {
            return read ? Decision.ALLOW : Decision.FORBIDDEN_WRITE;
        }
        return read ? Decision.FORBIDDEN_READ : Decision.FORBIDDEN_WRITE;
    }

    static boolean inNamespace(String uri, String namespace) {
        return uri.equals(namespace) || uri.startsWith(namespace + "/");
    }
}
