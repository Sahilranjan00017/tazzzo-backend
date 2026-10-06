package com.tazzzo.catalog.api;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * The ONE classification of Spring MVC request-shape failures for the controller-scoped {@code /v1} error boundaries.
 *
 * <p>Each of those boundaries ({@code assignableTypes} advice at {@code HIGHEST_PRECEDENCE}) ends in an
 * {@code @ExceptionHandler(Exception.class)} catch-all, and the framework raises these exceptions while resolving the
 * arguments or writing the result of a controller method -- so without this seam an unreadable body, an unsupported
 * {@code Content-Type} or an unacceptable {@code Accept} became a logged-as-ERROR 500. Every catch-all asks
 * {@link #classify} first and answers a classified failure as the client error it is, in its own documented shape.
 *
 * <p>A transport primitive like {@link RequestIdFilter}: neutral, owned by no domain.
 */
public final class ClientRequestErrors {

    private ClientRequestErrors() { }

    /** A request-shape failure: the client's fault, a single WARN line, never a 500 or a stack trace. */
    public enum Kind {
        /** The body could not be read, or a header / parameter / path value is missing or has the wrong type. */
        MALFORMED(HttpStatus.BAD_REQUEST, "invalid request"),
        /** The body's {@code Content-Type} (or its absence) is not one the route reads. */
        UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported media type"),
        /** The route has no representation the {@code Accept} header allows. */
        NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "not acceptable");

        private final HttpStatus status;
        private final String message;

        Kind(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }

        public HttpStatus status() {
            return status;
        }

        /** Generic by design: the exception's own text names Java types and echoes client input. */
        public String message() {
            return message;
        }
    }

    /** The kind of request-shape failure {@code e} is, or {@code null} when it is not one (a genuine server fault). */
    public static Kind classify(Throwable e) {
        if (e instanceof HttpMediaTypeNotSupportedException) {
            return Kind.UNSUPPORTED_MEDIA_TYPE;
        }
        if (e instanceof HttpMediaTypeNotAcceptableException) {
            return Kind.NOT_ACCEPTABLE;
        }
        if (e instanceof HttpMessageNotReadableException
                || e instanceof ServletRequestBindingException // missing header / parameter / path variable
                || e instanceof TypeMismatchException          // a path or query value of the wrong type
                || e instanceof BindException                  // includes MethodArgumentNotValidException
                || e instanceof HandlerMethodValidationException
                || e instanceof MissingServletRequestPartException) {
            return Kind.MALFORMED;
        }
        return null;
    }
}
