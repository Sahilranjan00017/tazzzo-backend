package com.tazzzo.media.admin;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.tx.ProductNotFoundException;
import com.tazzzo.media.InvalidMediaException;
import com.tazzzo.media.MediaConflictException;
import com.tazzzo.media.MediaNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The admin error envelope for the media admin API only. */
@RestControllerAdvice(assignableTypes = MediaAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class MediaAdminExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MediaAdminExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ErrorBody> noProduct(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such product", req);
    }

    @ExceptionHandler(MediaNotFoundException.class)
    ResponseEntity<ErrorBody> noSet(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no media set for this owner", req);
    }

    @ExceptionHandler(MediaConflictException.class)
    ResponseEntity<ErrorBody> conflict(MediaConflictException e, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STALE_VERSION", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidMediaException.class)
    ResponseEntity<ErrorBody> invalid(InvalidMediaException e, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MEDIA", e.getMessage(), req);
    }

    @ExceptionHandler(MediaAdminController.MediaStorageUnavailableException.class)
    ResponseEntity<ErrorBody> noStorage(HttpServletRequest req) {
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_NOT_CONFIGURED", "media storage is not configured", req);
    }

    /** The configured store could not be reached or refused us: an outage, reported as such (class name only, no stack). */
    @ExceptionHandler(com.tazzzo.media.MediaStorageFailure.class)
    ResponseEntity<ErrorBody> storageFailed(com.tazzzo.media.MediaStorageFailure e, HttpServletRequest req) {
        log.warn("media_storage_unavailable type={} request_id={}", e.getMessage(), req.getAttribute(RequestIdFilter.REQUEST_ID));
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE", "media storage is unavailable", req);
    }

    private static ResponseEntity<ErrorBody> envelope(HttpStatus status, String code, String message, HttpServletRequest req) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON) // never negotiated by Accept
                .body(new ErrorBody(body));
    }
}
