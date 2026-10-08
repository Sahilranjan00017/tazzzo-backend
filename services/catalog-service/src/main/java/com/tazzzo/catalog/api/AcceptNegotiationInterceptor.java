package com.tazzzo.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.function.Supplier;

/**
 * Negotiates {@code Accept} BEFORE a handler runs, so a 406 never follows a committed write.
 *
 * <p>Spring MVC otherwise negotiates the response representation only when it WRITES the handler's return value: by
 * then an address, cart line, quote, order, support case or account deletion has already been committed, and the client
 * is told 406 for a request that succeeded. Every application handler that returns a body writes JSON (Jackson:
 * {@code application/json} and {@code application/*+json}), so the decision can be made up front from the request alone.
 * A request whose {@code Accept} admits neither is refused here with {@link HttpMediaTypeNotAcceptableException}, which
 * Spring resolves with the selected handler -- each route keeps its own controller-scoped error shape.
 *
 * <p>Deliberately unchanged: servlet filters (request id, body limit, CORS, authentication, rate limiting) all run
 * before this, so a missing token is still a 401 whatever the {@code Accept}; handlers with no body to negotiate
 * ({@code void}, {@code ResponseEntity<Void>}) are not checked; framework and library handlers (springdoc, actuator,
 * error pages) are not checked; and the compatibility test is Spring's own ({@link MediaType#isCompatibleWith}), so
 * every {@code Accept} that reached a 2xx before still does.
 */
public final class AcceptNegotiationInterceptor implements HandlerInterceptor {

    /** What Jackson writes for an application handler's body. */
    static final List<MediaType> PRODUCIBLE = List.of(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));

    private static final String APPLICATION_PACKAGE = "com.tazzzo.";

    private final Supplier<ContentNegotiationManager> negotiation;

    public AcceptNegotiationInterceptor(Supplier<ContentNegotiationManager> negotiation) {
        this.negotiation = negotiation;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws HttpMediaTypeNotAcceptableException {
        if (!(handler instanceof HandlerMethod method) || !writesBody(method)) {
            return true;
        }
        for (MediaType acceptable : negotiation.get().resolveMediaTypes(new ServletWebRequest(request))) {
            for (MediaType producible : PRODUCIBLE) {
                if (acceptable.isCompatibleWith(producible)) {
                    return true;
                }
            }
        }
        throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
    }

    /** An application handler whose return value is written as a response body. */
    static boolean writesBody(HandlerMethod method) {
        if (!method.getBeanType().getName().startsWith(APPLICATION_PACKAGE)) {
            return false;
        }
        Class<?> type = method.getReturnType().getParameterType();
        if (type == void.class || type == Void.class) {
            return false;
        }
        if (HttpEntity.class.isAssignableFrom(type)) {
            Class<?> body = ResolvableType.forMethodParameter(method.getReturnType()).as(HttpEntity.class).resolveGeneric(0);
            return body != Void.class;
        }
        return true;
    }
}
