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
import java.util.regex.Pattern;

/**
 * {@code request_id} per request (always server-minted), {@code correlation_id} passed through from the client when
 * it is well formed; both in MDC for structured logs and both echoed as response headers.
 *
 * <p>The inbound {@code X-Correlation-Id} is untrusted text that would otherwise reach every log line verbatim
 * (log injection: CR/LF, control characters, unbounded length, terminal escapes). It is accepted ONLY when it matches
 * {@link #CORRELATION_ID_SHAPE}; anything else is dropped silently (never logged, never echoed), and the request simply
 * has no correlation id. The server-minted request id is never influenced by the client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "request_id";
    public static final String CORRELATION_ID = "correlation_id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** 1..64 characters: letters, digits, dot, underscore, colon, hyphen; must start with a letter or digit. */
    static final Pattern CORRELATION_ID_SHAPE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        req.setAttribute(REQUEST_ID, requestId);
        MDC.put(REQUEST_ID, requestId);
        String correlationId = acceptedCorrelationId(req.getHeader(CORRELATION_HEADER));
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
            req.setAttribute(CORRELATION_ID, correlationId);
            res.setHeader(CORRELATION_HEADER, correlationId);
        }
        res.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }

    /** The header value if it is safe to log and echo, otherwise {@code null}. */
    static String acceptedCorrelationId(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        return CORRELATION_ID_SHAPE.matcher(value).matches() ? value : null;
    }
}
