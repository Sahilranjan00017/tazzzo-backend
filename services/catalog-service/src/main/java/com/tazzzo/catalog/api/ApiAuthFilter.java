package com.tazzzo.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.admin.auth.AdminAuthRejection;
import com.tazzzo.admin.auth.AdminAccessPolicy;
import com.tazzzo.admin.auth.AdminAuthentication;
import com.tazzzo.admin.auth.AdminAuthenticatorChain;
import com.tazzzo.admin.auth.AdminBearerCredential;
import com.tazzzo.admin.auth.AdminPrincipal;
import com.tazzzo.admin.auth.AdminPrincipalResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Admin authentication + operation-class authorization (spec Part 16), applied to the INTERNAL surface as classified by
 * {@link SurfaceClassifier}. Two separate stages:
 * <ol>
 *   <li><b>authentication</b>: the bearer credential goes to {@link AdminAuthenticatorChain} (exact shared service token
 *       first, then Google OIDC for human admins), which yields an {@code AdminPrincipal} or a bounded refusal;</li>
 *   <li><b>authorization</b>: writes require cms-writer; reads require reader or cms-writer, whatever the credential
 *       family. A principal holding ONLY the narrow audit-reader role may GET exactly {@link #NARROW_READ_PATHS} (exact
 *       URI match, so any encoded or decorated variant fails closed); the audit endpoint itself enforces audit-reader.</li>
 * </ol>
 * The same typed principal is attached for {@link AdminActors} either way. The PUBLIC consumer namespace bypasses this
 * filter by decision (Q4-a/b); an UNKNOWN surface is refused by default (Q4-f). Deliberately small and auditable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3) // after request-id, body-size limit and CORS (platform baseline), before customer auth
public class ApiAuthFilter extends OncePerRequestFilter {

    /** The only routes a principal without reader/cms-writer (i.e. audit-reader alone) may reach. */
    static final Set<String> NARROW_READ_PATHS =
            Set.of("/api/v1/admin/me", "/api/v1/admin/audit-events");

    /** A staff-only principal also reaches {@code /me} (and nothing else outside its own namespace). */
    static final Set<String> STAFF_NARROW_READ_PATHS = Set.of("/api/v1/admin/me");

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);
    private static final String UNAUTHENTICATED_MESSAGE = "missing or unknown bearer token";

    private final AdminAuthenticatorChain authenticators;
    private final AdminAuthObservability observability;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiAuthFilter(AdminAuthenticatorChain authenticators, AdminAuthObservability observability) {
        this.authenticators = authenticators;
        this.observability = observability;
    }

    /**
     * Q4-a / Q4-b: the PUBLIC consumer namespace bypasses service-token authentication ENTIRELY.
     * A bearer header on a public URL is irrelevant to authority — anonymous, bogus, read and cms
     * identities all reach the same downstream capability. Public means "the header does not
     * matter", not "anonymous only".
     *
     * <p><b>PR-11A:</b> the CUSTOMER_AUTHENTICATED surface ({@code /v1/customer/**}) is ALSO
     * skipped here — {@link com.tazzzo.auth.CustomerAuthFilter} owns that boundary exclusively.
     * A CMS/read service token must never be able to authorize a customer route, and this filter
     * must never demand one of a customer request; each trust domain has exactly one filter that
     * can grant it. Every other surface (INTERNAL, UNKNOWN) still runs through this filter.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        SurfaceClassifier.Surface surface = SurfaceClassifier.classify(req.getRequestURI());
        return surface == SurfaceClassifier.Surface.PUBLIC_CONSUMER
                || surface == SurfaceClassifier.Surface.CUSTOMER_AUTHENTICATED
                || surface == SurfaceClassifier.Surface.HEALTH; // probes carry no credential; the controller serves no data
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // Q4-f: an UNKNOWN surface is refused BEFORE credentials are even read. "Unknown path +
        // valid internal token -> pass through just in case" is exactly the hole this closes: a
        // future actuator, debug endpoint or controller must not become reachable merely by
        // existing on the classpath. Same code and envelope the advice uses for a missing route.
        if (SurfaceClassifier.classify(req.getRequestURI()) == SurfaceClassifier.Surface.UNKNOWN) {
            reject(req, res, 404, "NO_SUCH_ENDPOINT", "no such endpoint");
            return;
        }
        // Stage A: authentication. The credential is never logged, stored or audited; only bounded reasons are.
        Optional<AdminBearerCredential> credential = AdminBearerCredential.fromAuthorizationHeader(req.getHeader("Authorization"));
        AdminAuthentication result = credential.isPresent()
                ? authenticators.authenticate(credential.get()) : AdminAuthentication.NOT_APPLICABLE;
        AdminPrincipal principal;
        switch (result) {
            case AdminAuthentication.Authenticated authenticated -> principal = authenticated.principal();
            case AdminAuthentication.Rejected rejected -> {
                refuse(req, res, rejected.reason());
                return;
            }
            case AdminAuthentication.NotApplicable notApplicable -> {
                observability.rejected(AdminAuthObservability.Reason.UNAUTHENTICATED);
                log.warn("admin_auth_rejected reason=unauthenticated request_id={}", req.getAttribute(RequestIdFilter.REQUEST_ID));
                reject(req, res, 401, "UNAUTHENTICATED", UNAUTHENTICATED_MESSAGE);
                return;
            }
        }
        // Stage B: operation authorization, decided in ONE place (AdminAccessPolicy), identical for every credential family.
        AdminAccessPolicy.Decision decision = AdminAccessPolicy.decide(principal, req.getMethod(), req.getRequestURI(),
                principal.isStaff() ? STAFF_NARROW_READ_PATHS : NARROW_READ_PATHS);
        if (decision != AdminAccessPolicy.Decision.ALLOW) {
            observability.rejected(AdminAuthObservability.Reason.FORBIDDEN);
            log.warn("admin_auth_rejected reason=forbidden actor_type={} request_id={}", principal.actorType(),
                    req.getAttribute(RequestIdFilter.REQUEST_ID));
            if (decision == AdminAccessPolicy.Decision.FORBIDDEN_WRITE) {
                reject(req, res, 403, "FORBIDDEN", "role may not perform writes here");
            } else {
                reject(req, res, 403, "FORBIDDEN", "role may not read this resource");
            }
            return;
        }
        AdminPrincipalResolver.attach(req, principal);
        chain.doFilter(req, res);
    }

    /**
     * A credential an authenticator recognised and refused. Every 401 carries the same body as a missing credential (no
     * oracle for which check failed); every allowlist refusal the same 403 body. The reason lives only in the bounded
     * metric and the log line (with the request id; never a subject, email or token).
     */
    private void refuse(HttpServletRequest req, HttpServletResponse res, AdminAuthRejection reason) throws IOException {
        AdminAuthObservability.Reason metric = switch (reason) {
            case INVALID_TOKEN -> AdminAuthObservability.Reason.INVALID_TOKEN;
            case EXPIRED_TOKEN -> AdminAuthObservability.Reason.EXPIRED_TOKEN;
            case DOMAIN_MISMATCH -> AdminAuthObservability.Reason.DOMAIN_MISMATCH;
            case EMAIL_UNVERIFIED -> AdminAuthObservability.Reason.EMAIL_UNVERIFIED;
            case NOT_ALLOWLISTED -> AdminAuthObservability.Reason.NOT_ALLOWLISTED;
            case DISABLED -> AdminAuthObservability.Reason.DISABLED;
        };
        observability.rejected(metric);
        log.warn("admin_auth_rejected reason={} request_id={}", metric.name().toLowerCase(Locale.ROOT),
                req.getAttribute(RequestIdFilter.REQUEST_ID));
        if (reason.httpStatus() == 403) {
            reject(req, res, 403, "FORBIDDEN", "admin access not granted");
        } else {
            reject(req, res, 401, "UNAUTHENTICATED", UNAUTHENTICATED_MESSAGE);
        }
    }

    private void reject(HttpServletRequest req, HttpServletResponse res, int status,
                        String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        Map<String, Object> body = Map.of("error", Map.of(
                "code", code, "message", message,
                "request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID))));
        mapper.writeValue(res.getWriter(), body);
    }
}
