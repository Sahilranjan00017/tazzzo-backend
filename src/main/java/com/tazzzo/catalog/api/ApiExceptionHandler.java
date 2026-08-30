package com.tazzzo.catalog.api;

import com.mongodb.MongoWriteException;
import com.tazzzo.catalog.tx.AttributeViolationException;
import com.tazzzo.catalog.tx.BundleComponentException;
import com.tazzzo.catalog.tx.CasConflictException;
import com.tazzzo.catalog.tx.EvidenceGateException;
import com.tazzzo.catalog.tx.IdentityCollisionException;
import com.tazzzo.catalog.tx.TaxonomyChangeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

/**
 * The stable error contract. Domain exceptions become machine-readable CODES; Java class
 * names never leak. The code is the public API and stays stable across implementation
 * changes. Every response carries request_id for audit correlation.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // M6: RELEASE_NOT_OPEN is a STATE conflict (409), never 404 — publish retries after a
    // successful activation must not be told the release does not exist.
    private static final Set<String> NOT_FOUND = Set.of("NODE_NOT_FOUND", "UNKNOWN_SCHEMA",
            "UNKNOWN_DEFINITION");
    private static final Set<String> CONFLICT = Set.of("NO_OPEN_RELEASE", "RELEASE_ALREADY_OPEN",
            "SCHEMA_CONFLICT", "DUPLICATE_NODE", "DUPLICATE_DEFINITION", "DUPLICATE_FIELD",
            "DUPLICATE_SCHEMA_VERSION", "NODE_NOT_ACTIVE", "HAS_ACTIVE_CHILDREN",
            "RELEASE_NOT_OPEN");

    public record ErrorBody(Map<String, String> error) { }

    @ExceptionHandler(TaxonomyChangeException.class)
    public ResponseEntity<ErrorBody> taxonomy(TaxonomyChangeException ex, HttpServletRequest req) {
        HttpStatus status = NOT_FOUND.contains(ex.code) ? HttpStatus.NOT_FOUND
                : CONFLICT.contains(ex.code) ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return envelope(status, ex.code, ex.getMessage(), req);
    }

    @ExceptionHandler(CasConflictException.class)
    public ResponseEntity<ErrorBody> stale(CasConflictException ex, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STALE_VERSION", ex.getMessage(), req);
    }

    @ExceptionHandler(IdentityCollisionException.class)
    public ResponseEntity<ErrorBody> identity(IdentityCollisionException ex, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "IDENTITY_COLLISION", ex.getMessage(), req);
    }

    @ExceptionHandler(AttributeViolationException.class)
    public ResponseEntity<ErrorBody> attributes(AttributeViolationException ex, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "ATTRIBUTE_VIOLATION", ex.getMessage(), req);
    }

    @ExceptionHandler(EvidenceGateException.class)
    public ResponseEntity<ErrorBody> evidence(EvidenceGateException ex, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_GATE", ex.getMessage(), req);
    }

    @ExceptionHandler(BundleComponentException.class)
    public ResponseEntity<ErrorBody> bundle(BundleComponentException ex, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "BUNDLE_COMPONENT", ex.getMessage(), req);
    }

    @ExceptionHandler(com.tazzzo.catalog.tx.ImmutableFieldException.class)
    public ResponseEntity<ErrorBody> immutableField(com.tazzzo.catalog.tx.ImmutableFieldException ex,
                                                    HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "IMMUTABLE_FIELD", ex.getMessage(), req);
    }

    @ExceptionHandler(com.tazzzo.catalog.tx.VariantPackException.class)
    public ResponseEntity<ErrorBody> variantPack(com.tazzzo.catalog.tx.VariantPackException ex,
                                                 HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "VARIANT_PACK_INVALID", ex.getMessage(), req);
    }

    @ExceptionHandler(com.tazzzo.catalog.tx.EvidenceImmutableException.class)
    public ResponseEntity<ErrorBody> evidenceImmutable(RuntimeException ex, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "EVIDENCE_IMMUTABLE", ex.getMessage(), req);
    }

    @ExceptionHandler(com.tazzzo.catalog.tx.EvidenceContractException.class)
    public ResponseEntity<ErrorBody> evidenceContract(
            com.tazzzo.catalog.tx.EvidenceContractException ex, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, ex.code, ex.getMessage(), req);
    }

    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ResponseEntity<ErrorBody> badTimestamp(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "observedAt must be an ISO-8601 instant, e.g. 2026-08-26T10:00:00Z", req);
    }

    @ExceptionHandler({NotFoundException.class, com.tazzzo.catalog.tx.ProductNotFoundException.class,
            com.tazzzo.catalog.tx.EvidenceNotFoundException.class})
    public ResponseEntity<ErrorBody> notFound(RuntimeException ex, HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), req);
    }

    /** M4: an untyped IllegalStateException from the domain is a STATE conflict, not a 404 —
     *  e.g. MergeService "both products must be active". Never masked as not-found. */
    @ExceptionHandler({IllegalStateException.class, com.tazzzo.catalog.tx.ProductStateException.class})
    public ResponseEntity<ErrorBody> stateConflict(RuntimeException ex, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STATE_CONFLICT", ex.getMessage(), req);
    }

    // M3: Spring MVC exceptions must map to their proper client codes; the catch-all below
    // would otherwise turn every missing header / bad method / wrong media type into a 500.
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> missingHeader(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.BAD_REQUEST, "MISSING_HEADER", ex.getMessage(), req);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> typeMismatch(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ex.getMessage(), req);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> methodNotAllowed(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), req);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> mediaType(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", ex.getMessage(), req);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> noRoute(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NO_SUCH_ENDPOINT", ex.getMessage(), req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ex, HttpServletRequest req) {
        return envelope(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ex.getMessage(), req);
    }

    /** m2: duplicate key is a conflict, not a validation failure — and the two must not
     *  share a code or a message. */
    @ExceptionHandler(MongoWriteException.class)
    public ResponseEntity<ErrorBody> mongo(MongoWriteException ex, HttpServletRequest req) {
        if (ex.getError().getCode() == 11000) {
            return envelope(HttpStatus.CONFLICT, "DUPLICATE_KEY", "identity already exists", req);
        }
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_VALIDATION",
                "document failed validation", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> internal(Exception ex, HttpServletRequest req) {
        log.error("unhandled", ex);
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", req);
    }

    private ResponseEntity<ErrorBody> envelope(HttpStatus status, String code, String message,
                                               HttpServletRequest req) {
        String requestId = String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID));
        log.warn("api_error code={} status={} request_id={}", code, status.value(), requestId);
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("request_id", requestId);
        Object correlation = req.getAttribute(RequestIdFilter.CORRELATION_ID);
        if (correlation != null) body.put("correlation_id", String.valueOf(correlation));
        return ResponseEntity.status(status).body(new ErrorBody(body));
    }
}
