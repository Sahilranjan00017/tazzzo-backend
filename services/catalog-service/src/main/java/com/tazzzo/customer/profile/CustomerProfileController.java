package com.tazzzo.customer.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR-12A — {@code GET/PATCH /v1/customer/profile}. Both endpoints sit on the pre-existing
 * {@code CUSTOMER_AUTHENTICATED} surface; {@code CustomerAuthFilter} already verified the bearer and
 * the session before either handler runs — this controller performs NO authentication of its own,
 * and reuses the ONE canonical seam ({@link CustomerPrincipalResolver#require}) every future
 * customer controller uses. {@code customerId} is NEVER accepted from the request body, a path
 * variable, a query parameter, or any header — it comes exclusively from the verified principal.
 *
 * <p>Optimistic concurrency via {@code ETag}/{@code If-Match} (never Mongo internals): the version
 * counter is the only concurrency-relevant field ever exposed publicly.
 */
@RestController
@RequestMapping("/v1/customer/profile")
public class CustomerProfileController {

    private static final int MAX_IF_MATCH_LENGTH = 40;
    private static final Pattern IF_MATCH_PATTERN = Pattern.compile("^\"?profile-([0-9]{1,15})\"?$");

    private final CustomerProfileService service;
    private final CustomerProfileObservability observability;

    public CustomerProfileController(CustomerProfileService service, CustomerProfileObservability observability) {
        this.service = service;
        this.observability = observability;
    }

    @GetMapping
    public ResponseEntity<CustomerProfileResponseDto> get(HttpServletRequest request) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        CustomerProfileService.ProfileView view = service.get(principal.customerId());
        return respond(view, requestId(request));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.CustomerProfilePatch.class)))
    @PatchMapping
    public ResponseEntity<CustomerProfileResponseDto> patch(HttpServletRequest request,
                                                             @RequestHeader(value = "If-Match", required = false)
                                                             String ifMatch,
                                                             @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        long expectedVersion = parseIfMatch(ifMatch);
        PatchField<String> displayName = extract(body, "displayName");
        PatchField<String> email = extract(body, "email");
        if (!displayName.isPresent() && !email.isPresent()) {
            // Neither recognized field was supplied -- an empty (or entirely-unrecognized) patch
            // is a deliberate 400, never a silent no-op (mission §8's chosen, documented behavior).
            throw fail(CustomerProfileFailure.Reason.INVALID_REQUEST);
        }
        CustomerProfileService.ProfileView view =
                service.patch(principal.customerId(), expectedVersion, displayName, email);
        return respond(view, requestId(request));
    }

    /** Records the SAME bounded update-failure metric a controller-level validation throw and a
     *  service-level failure both count under — the reason enum is the single source of truth for
     *  the tag vocabulary. */
    private CustomerProfileFailure fail(CustomerProfileFailure.Reason reason) {
        observability.updateFailure(reason);
        return new CustomerProfileFailure(reason);
    }

    private static ResponseEntity<CustomerProfileResponseDto> respond(CustomerProfileService.ProfileView view,
                                                                      String requestId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, quotedEtag(view.version()))
                .body(new CustomerProfileResponseDto(view.customerId(), view.displayName(), view.email(),
                        view.version(), requestId));
    }

    private static String quotedEtag(long version) {
        return "\"profile-" + version + "\"";
    }

    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw fail(CustomerProfileFailure.Reason.PRECONDITION_REQUIRED);
        }
        String trimmed = ifMatch.trim();
        if (trimmed.length() > MAX_IF_MATCH_LENGTH) {
            throw fail(CustomerProfileFailure.Reason.INVALID_REQUEST);
        }
        Matcher m = IF_MATCH_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            throw fail(CustomerProfileFailure.Reason.INVALID_REQUEST);
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw fail(CustomerProfileFailure.Reason.INVALID_REQUEST);
        }
    }

    /** ABSENT if the key is not in the body at all; PRESENT(null) for an explicit JSON null;
     *  PRESENT(value) for a string value. A non-string, non-null value is a malformed shape. */
    private PatchField<String> extract(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.has(field)) {
            return PatchField.absent();
        }
        JsonNode value = body.get(field);
        if (value.isNull()) {
            return PatchField.of(null);
        }
        if (!value.isTextual()) {
            throw fail(CustomerProfileFailure.Reason.INVALID_REQUEST);
        }
        return PatchField.of(value.textValue());
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
