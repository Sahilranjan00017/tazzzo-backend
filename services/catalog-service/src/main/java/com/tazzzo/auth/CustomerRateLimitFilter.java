package com.tazzzo.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.api.SurfaceClassifier;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketDimension;
import com.tazzzo.catalog.ratelimit.BucketSpec;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Per-customer admission on {@code /v1/customer/**}, AFTER {@link CustomerAuthFilter} has verified the session (so the key is
 * the verified customer id, never anything the client chose, and an unauthenticated request is a 401 before it costs
 * anything). GET/HEAD charge the read bucket; every other method the write bucket. A refusal is 429 with Retry-After; a
 * store outage FAILS CLOSED with 503 (an unlimited customer surface is not a degraded mode we choose silently).
 */
public class CustomerRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CustomerRateLimitFilter.class);
    static final String READ_BUCKET_PREFIX = "rl:customer:read:";
    static final String WRITE_BUCKET_PREFIX = "rl:customer:write:";

    private final RateLimitStore store;
    private final CustomerRateLimitProperties properties;
    private final MeterRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public CustomerRateLimitFilter(RateLimitStore store, CustomerRateLimitProperties properties, MeterRegistry registry) {
        this.store = store;
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SurfaceClassifier.classify(request.getRequestURI()) != SurfaceClassifier.Surface.CUSTOMER_AUTHENTICATED;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Optional<CustomerPrincipal> principal = CustomerPrincipalResolver.current(req);
        if (principal.isEmpty()) {
            chain.doFilter(req, res);   // not authenticated: CustomerAuthFilter already answered, or will
            return;
        }
        boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        CustomerRateLimitProperties.Bucket b = read ? properties.getReads() : properties.getWrites();
        BucketDimension dim = read ? BucketDimension.CUSTOMER_READ : BucketDimension.CUSTOMER_WRITE;
        Admission admission;
        try {
            admission = store.tryConsume(List.of(new BucketSpec(dim,
                    (read ? READ_BUCKET_PREFIX : WRITE_BUCKET_PREFIX) + principal.get().customerId().value(), b.getCapacity(),
                    b.getRefillPerSecond())), 1);
        } catch (RuntimeException e) {
            admission = new Admission.Unavailable(e.getClass().getSimpleName());
        }
        String kind = read ? "read" : "write";
        switch (admission) {
            case Admission.Allowed allowed -> {
                count("allowed", kind);
                chain.doFilter(req, res);
            }
            case Admission.RateLimited limited -> {
                count("limited", kind);
                long seconds = Math.max(1, (limited.retryAfter().toMillis() + 999) / 1000);
                res.setHeader("Retry-After", Long.toString(seconds));
                write(req, res, 429, "RATE_LIMITED", "too many requests");
            }
            case Admission.Unavailable unavailable -> {
                count("unavailable", kind);
                log.warn("customer_rate_limit_unavailable request_id={}", requestId(req));
                write(req, res, 503, "SERVICE_UNAVAILABLE", "service unavailable");
            }
        }
    }

    private void count(String outcome, String kind) {
        try {
            Counter.builder("customer_rate_limit").tag("outcome", outcome).tag("kind", kind).register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("customer rate limit metric failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }

    private void write(HttpServletRequest req, HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setHeader("Cache-Control", "no-store");
        mapper.writeValue(res.getWriter(), new CustomerAuthErrorDto(code, message, requestId(req)));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
