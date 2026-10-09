package com.tazzzo.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refuses a request whose query string the container cannot bind, once and early, for every surface.
 *
 * <p>Tomcat 11 (Spring Boot 4) throws {@code InvalidParameterException} (an {@link IllegalStateException}) from
 * {@code getParameterMap()} for an undecodable parameter ({@code %ZZ}, invalid UTF-8) or more parameters than
 * {@code server.tomcat.max-parameter-count}; Tomcat 10 silently dropped them. Left alone that surfaces lazily, wherever a
 * controller first reads a parameter, as a 409/500 whose message echoes the parameter. Here the parameters are bound
 * up front and a failure is the surface's own 400 malformed-request envelope with a FIXED message that never carries
 * the parameter name, value or limit.
 *
 * <p>Ordered AFTER the platform filters, both authentication filters and the customer rate limiter, so an
 * unauthenticated caller is still answered by authentication first and gets no more than before. Only the
 * authenticated or public surfaces are inspected; UNKNOWN and HEALTH requests pass through untouched. A well-formed
 * request is passed on unchanged (the parameters are cached by the container, not re-parsed).
 */
@Component
@Order(MalformedQueryFilter.ORDER)
public class MalformedQueryFilter extends OncePerRequestFilter {

    /** After the customer rate limiter ({@code HIGHEST_PRECEDENCE + 20}). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 21;

    public static final String INTERNAL_CODE = "MALFORMED_REQUEST";
    public static final String INTERNAL_MESSAGE = "query string is malformed";
    public static final String PUBLIC_CODE = "INVALID_REQUEST";
    public static final String PUBLIC_MESSAGE = "invalid request";

    /**
     * Owns its raw-query refusal (RawQuerySyntax.bind): it runs AFTER the audit-read authorisation and counts each refusal in
     * its own metric, both of which must keep preceding it.
     */
    static final String AUDIT_EVENTS = "/api/v1/admin/audit-events";

    private static final Logger log = LoggerFactory.getLogger(MalformedQueryFilter.class);

    private final ObjectMapper mapper;

    public MalformedQueryFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        SurfaceClassifier.Surface surface = SurfaceClassifier.classify(req.getRequestURI());
        if (surface == SurfaceClassifier.Surface.UNKNOWN || surface == SurfaceClassifier.Surface.HEALTH
                || AUDIT_EVENTS.equals(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        try {
            req.getParameterMap();
        } catch (IllegalStateException e) {
            reject(req, res, surface);
            return;
        }
        chain.doFilter(req, res);
    }

    private void reject(HttpServletRequest req, HttpServletResponse res, SurfaceClassifier.Surface surface)
            throws IOException {
        String requestId = String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID));
        log.warn("malformed_query status=400 request_id={}", requestId); // never the parameter or the exception text
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        Object body;
        if (surface == SurfaceClassifier.Surface.INTERNAL) {
            Map<String, String> error = new LinkedHashMap<>();
            error.put("code", INTERNAL_CODE);
            error.put("message", INTERNAL_MESSAGE);
            error.put("request_id", requestId);
            Object correlation = req.getAttribute(RequestIdFilter.CORRELATION_ID);
            if (correlation != null) error.put("correlation_id", String.valueOf(correlation));
            body = new ApiExceptionHandler.ErrorBody(error);
        } else {
            body = new com.tazzzo.catalog.consumer.ConsumerDtos.ConsumerError(PUBLIC_CODE, PUBLIC_MESSAGE, requestId);
        }
        mapper.writeValue(res.getWriter(), body);
    }
}
