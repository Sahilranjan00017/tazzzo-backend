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
 * Times every internal/unclassified request around the whole chain and hands the outcome to {@link AdminHttpMetrics}. It
 * only observes: it reads the status after the chain returned and never touches the request, the response or the exception.
 *
 * <p>Order: the outermost slot, the same as {@link RequestIdFilter} and {@link SecurityHeadersFilter} (which it does not
 * depend on), so it also sees what the later filters answer themselves: the body-size refusal (413), a CORS refusal, the
 * service-token refusals (401/403) and the malformed-query refusal. Pinned by {@code PlatformFilterOrderTest}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminHttpMetricsFilter extends OncePerRequestFilter {

    private final AdminHttpMetrics metrics;

    public AdminHttpMetricsFilter(AdminHttpMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        SurfaceClassifier.Surface surface = SurfaceClassifier.classify(req.getRequestURI());
        if (!AdminHttpMetrics.covers(surface)) {
            chain.doFilter(req, res);
            return;
        }
        long started = System.nanoTime();
        int status = 500;   // an exception that escapes the chain is a 500 to the container
        try {
            chain.doFilter(req, res);
            status = res.getStatus();
        } finally {
            metrics.record(req, surface, status, System.nanoTime() - started);
        }
    }
}
