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
import java.util.HashMap;
import java.util.Map;

/**
 * Service-token authentication + operation-class authorization (spec Part 16).
 * Bearer token maps to a role; writes require cms-writer, reads accept any known role.
 * Deliberately small and auditable: the API is a consumer of catalogue truth, not its owner.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiAuthFilter extends OncePerRequestFilter {

    private final Map<String, String> tokenRoles = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * M7: NO default credentials. An unset token disables that role entirely (fail-closed);
     * production cannot silently inherit a source-tree token. Writer is registered last so a
     * misconfiguration that reuses one value cannot demote the writer to reader.
     */
    public ApiAuthFilter(@Value("${tazzzo.auth.cms-token:}") String cmsToken,
                         @Value("${tazzzo.auth.read-token:}") String readToken) {
        if (readToken != null && !readToken.isBlank()) tokenRoles.put(readToken, "reader");
        if (cmsToken != null && !cmsToken.isBlank()) tokenRoles.put(cmsToken, "cms-writer");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        String role = null;
        if (header != null && header.startsWith("Bearer ")) {
            role = tokenRoles.get(header.substring(7));
        }
        if (role == null) {
            reject(req, res, 401, "UNAUTHENTICATED", "missing or unknown bearer token");
            return;
        }
        if (!"GET".equals(req.getMethod()) && !"cms-writer".equals(role)) {
            reject(req, res, 403, "FORBIDDEN", "role may not perform writes: " + role);
            return;
        }
        req.setAttribute("auth_role", role);
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
