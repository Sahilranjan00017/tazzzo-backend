package com.tazzzo.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.api.SurfaceClassifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * PR-11A — the customer bearer-token authentication boundary. Runs ONLY for
 * {@link SurfaceClassifier.Surface#CUSTOMER_AUTHENTICATED} — {@code ApiAuthFilter} explicitly skips
 * that surface (it owns INTERNAL only), so exactly ONE filter ever decides authority for
 * {@code /v1/customer/**}, and neither a CMS/read service token nor an unauthenticated public
 * request can reach it.
 *
 * <p><b>Accepts ONLY</b> {@code Authorization: Bearer <token>}. Never a query parameter, cookie,
 * {@code X-Tazzzo-Installation-Id}, or request body — there is no silent fallback identity, and the
 * installation id remains what it has always been: an optional anti-abuse dimension, never
 * authentication (CAT-SEC-1 Q5-INSTALL-1).
 *
 * <p><b>PR-11C — session revocation authority.</b> Cryptographic/time validity alone is
 * insufficient once real sessions exist: a stolen-but-unexpired token must stop working the moment
 * its session is revoked (logout). After {@link CustomerAccessTokenCodec#verify} succeeds, this
 * filter ALSO consults {@link SessionAuthority#isSessionActive}. A missing {@link SessionAuthority}
 * bean is NOT treated as "skip the check" — it fails closed exactly like a missing signing key,
 * because silently bypassing revocation would be a security regression, not a graceful degradation.
 *
 * <p><b>401 vs 503.</b> Every CREDENTIAL failure — missing header, malformed bearer, bad signature,
 * expired token, revoked/unknown session — flattens to the SAME {@code 401 UNAUTHENTICATED} with a
 * generic message. But {@link CustomerAuthFailure.Reason#NOT_READY} (the signing key is
 * missing/malformed, or the {@link SessionAuthority} dependency is absent or itself failing) is a
 * SERVER misconfiguration, not a bad credential, and is deliberately mapped to
 * {@code 503 SERVICE_UNAVAILABLE} instead — presenting a perfectly valid token to a broken
 * deployment must never look identical to presenting an invalid one. The bounded
 * {@link CustomerAuthFailure.Reason} is logged internally only, never in either response body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4) // after the platform filters and the service-token filter
public class CustomerAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final CustomerAccessTokenCodec codec;
    private final ObjectProvider<SessionAuthority> sessionAuthority;
    private final CustomerAuthObservability observability;
    private final ObjectMapper mapper = new ObjectMapper();

    public CustomerAuthFilter(CustomerAccessTokenCodec codec, ObjectProvider<SessionAuthority> sessionAuthority,
                              CustomerAuthObservability observability) {
        this.codec = codec;
        this.sessionAuthority = sessionAuthority;
        this.observability = observability;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SurfaceClassifier.classify(request.getRequestURI())
                != SurfaceClassifier.Surface.CUSTOMER_AUTHENTICATED;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(request, response, CustomerAuthFailure.Reason.MISSING);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length());
        try {
            CustomerPrincipal principal = codec.verify(token);
            SessionAuthority authority = sessionAuthority.getIfAvailable();
            if (authority == null) {
                // Fail closed — a missing revocation authority must never be treated as "no
                // sessions to revoke". This mirrors the codec's own NOT_READY posture: a SERVER
                // misconfiguration, mapped to 503, never a fake 401.
                reject(request, response, CustomerAuthFailure.Reason.NOT_READY);
                return;
            }
            boolean active;
            try {
                active = authority.isSessionActive(principal.customerId(), principal.sessionId());
            } catch (RuntimeException e) {
                // The authority's backing dependency (Mongo, etc) itself failed — this is STILL a
                // server-readiness problem, not evidence the credential is bad.
                log.error("session_authority_failed type={}", e.getClass().getSimpleName());
                reject(request, response, CustomerAuthFailure.Reason.NOT_READY);
                return;
            }
            if (!active) {
                reject(request, response, CustomerAuthFailure.Reason.SESSION_INACTIVE);
                return;
            }
            request.setAttribute(CustomerPrincipalResolver.ATTRIBUTE, principal);
            chain.doFilter(request, response);
        } catch (CustomerAuthFailure e) {
            reject(request, response, e.reason());
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        CustomerAuthFailure.Reason reason) throws IOException {
        String requestId = requestId(request);
        log.warn("customer_auth_rejected reason={} request_id={}", reason, requestId);
        observability.authRejected(reason);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        if (reason == CustomerAuthFailure.Reason.NOT_READY) {
            // Server misconfiguration/dependency-unavailable — never a fake 401. Body stays
            // generic: no hint of WHICH dependency (signing key vs session authority) failed.
            response.setStatus(503);
            mapper.writeValue(response.getWriter(),
                    new CustomerAuthErrorDto("SERVICE_UNAVAILABLE", "service unavailable", requestId));
            return;
        }
        response.setStatus(401);
        // Generic challenge only — never the bounded internal reason (expired/bad_signature/...).
        // A client learns nothing beyond "present a bearer token".
        response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getWriter(),
                new CustomerAuthErrorDto("UNAUTHENTICATED", "authentication required", requestId));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
