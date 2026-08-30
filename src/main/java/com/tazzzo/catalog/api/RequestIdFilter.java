package com.tazzzo.catalog.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** request_id per request, correlation_id passed through; both in MDC for structured logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "request_id";
    public static final String CORRELATION_ID = "correlation_id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String correlationId = req.getHeader("X-Correlation-Id");
        req.setAttribute(REQUEST_ID, requestId);
        MDC.put(REQUEST_ID, requestId);
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
            req.setAttribute(CORRELATION_ID, correlationId);
        }
        res.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
