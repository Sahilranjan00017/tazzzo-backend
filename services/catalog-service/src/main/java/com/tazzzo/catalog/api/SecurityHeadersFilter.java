package com.tazzzo.catalog.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Defensive response headers on EVERY response, including the 401/403/429/503 that filters write before routing (they
 * are set before the chain runs). The service serves JSON only, never HTML, so the policy is maximal: nothing may be
 * framed, sniffed, embedded or referred. HSTS tells browsers to stay on HTTPS once they have seen the production origin;
 * browsers ignore it over plain HTTP, so local development is unaffected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String HSTS = "max-age=31536000; includeSubDomains";
    static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Content-Security-Policy", CSP);
        res.setHeader("Strict-Transport-Security", HSTS);
        res.setHeader("Cross-Origin-Resource-Policy", "same-site");
        res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        chain.doFilter(req, res);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
