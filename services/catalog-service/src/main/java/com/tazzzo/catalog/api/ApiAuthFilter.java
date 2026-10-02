package com.tazzzo.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import com.tazzzo.admin.auth.AdminPrincipal;
import com.tazzzo.admin.auth.AdminPrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Service-token authentication + operation-class authorization (spec Part 16), applied to the
 * INTERNAL surface as classified by {@link SurfaceClassifier}. Bearer token maps to an {@code AdminPrincipal}
 * (the shared tokens are SERVICE_ACCOUNT principals, attached for {@link AdminActors}); writes
 * require cms-writer, reads accept any known role. The PUBLIC consumer namespace bypasses this
 * filter by decision (Q4-a/b); an UNKNOWN surface is refused by default (Q4-f).
 * Deliberately small and auditable: the API is a consumer of catalogue truth, not its owner.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    /** Token value -> its principal. The token itself never leaves this map: it is not logged, stored or audited. */
    private final Map<String, AdminPrincipal> tokenPrincipals = new HashMap<>();
    private final AdminAuthObservability observability;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * M7: NO default credentials. An unset token disables that role entirely (fail-closed);
     * production cannot silently inherit a source-tree token. Writer is registered last so a
     * misconfiguration that reuses one value cannot demote the writer to reader.
     */
    public ApiAuthFilter(@Value("${tazzzo.auth.cms-token:}") String cmsToken,
                         @Value("${tazzzo.auth.read-token:}") String readToken,
                         AdminAuthObservability observability) {
        this.observability = observability;
        // Insertion order is deliberate and unchanged: if both properties were (mis)configured to the SAME value, the
        // cms-writer entry replaces the reader entry, exactly as before this principal mapping existed.
        if (readToken != null && !readToken.isBlank()) {
            tokenPrincipals.put(readToken, AdminPrincipal.sharedToken(AdminPrincipal.READER));
        }
        if (cmsToken != null && !cmsToken.isBlank()) {
            tokenPrincipals.put(cmsToken, AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER));
        }
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
                || surface == SurfaceClassifier.Surface.CUSTOMER_AUTHENTICATED;
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
        String header = req.getHeader("Authorization");
        AdminPrincipal principal = null;
        if (header != null && header.startsWith("Bearer ")) {
            principal = tokenPrincipals.get(header.substring(7));
        }
        if (principal == null) {
            observability.rejected(AdminAuthObservability.Reason.UNAUTHENTICATED);
            log.warn("admin_auth_rejected reason=unauthenticated request_id={}", req.getAttribute(RequestIdFilter.REQUEST_ID));
            reject(req, res, 401, "UNAUTHENTICATED", "missing or unknown bearer token");
            return;
        }
        if (!"GET".equals(req.getMethod()) && !principal.canWrite()) {
            observability.rejected(AdminAuthObservability.Reason.FORBIDDEN);
            log.warn("admin_auth_rejected reason=forbidden actor={} request_id={}", principal.actorId(),
                    req.getAttribute(RequestIdFilter.REQUEST_ID));
            reject(req, res, 403, "FORBIDDEN", "role may not perform writes: " + AdminPrincipal.READER);
            return;
        }
        AdminPrincipalResolver.attach(req, principal);
        chain.doFilter(req, res);
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
