package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PR-12B — {@code /v1/customer/addresses}. Sits on the pre-existing {@code CUSTOMER_AUTHENTICATED}
 * surface; {@code CustomerAuthFilter} already verified the bearer/session before any handler runs.
 * {@code customerId} is NEVER accepted from the request — always the verified principal.
 * {@code addressId} always comes from the path AND is always re-scoped to the caller's own
 * customerId at the repository layer — there is no {@code GET /v1/customer/{customerId}/addresses}.
 */
@RestController
@RequestMapping("/v1/customer/addresses")
public class AddressController {

    private static final int MAX_IF_MATCH_LENGTH = 40;
    private static final Pattern IF_MATCH_PATTERN = Pattern.compile("^\"?address-([0-9]{1,15})\"?$");

    private final AddressService service;
    private final AddressServiceabilityEvaluator serviceability;
    private final AddressObservability observability;

    public AddressController(AddressService service, AddressServiceabilityEvaluator serviceability,
                             AddressObservability observability) {
        this.service = service;
        this.serviceability = serviceability;
        this.observability = observability;
    }

    @GetMapping
    public ResponseEntity<AddressListResponseDto> list(HttpServletRequest request) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        List<AddressService.AddressView> views = service.list(principal.customerId());
        String requestId = requestId(request);
        List<AddressResponseDto> items = views.stream().map(v -> present(v, requestId))
                .collect(Collectors.toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AddressListResponseDto(items, requestId));
    }

    @PostMapping
    public ResponseEntity<AddressResponseDto> create(HttpServletRequest request,
                                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                                      String idempotencyKey,
                                                      @RequestBody AddressCreateRequestDto body) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        AddressService.CreateCommand cmd = new AddressService.CreateCommand(body.label(), body.recipientName(),
                body.recipientPhone(), body.addressLine1(), body.addressLine2(), body.landmark(), body.city(),
                body.state(), body.postalCode(), body.latitude(), body.longitude());
        // optional: without the header a create is not idempotent (unchanged behaviour); with it, a retry is safe
        AddressService.AddressView created = service.create(principal.customerId(), cmd, idempotencyKey);
        return respond(created, requestId(request), 201);
    }

    @GetMapping("/{addressId}")
    public ResponseEntity<AddressResponseDto> get(HttpServletRequest request, @PathVariable String addressId) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        AddressService.AddressView view = service.get(principal.customerId(), parseAddressId(addressId));
        return respond(view, requestId(request), 200);
    }

    @PatchMapping("/{addressId}")
    public ResponseEntity<AddressResponseDto> patch(HttpServletRequest request, @PathVariable String addressId,
                                                     @RequestHeader(value = "If-Match", required = false)
                                                     String ifMatch,
                                                     @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        long expectedVersion = parseIfMatch(ifMatch);
        AddressService.PatchCommand cmd = new AddressService.PatchCommand(
                extract(body, "label"), extract(body, "recipientName"), extract(body, "recipientPhone"),
                extract(body, "addressLine1"), extract(body, "addressLine2"), extract(body, "landmark"),
                extract(body, "city"), extract(body, "state"), extract(body, "postalCode"),
                extractDouble(body, "latitude"), extractDouble(body, "longitude"));
        AddressService.AddressView updated = service.patch(principal.customerId(), parseAddressId(addressId),
                expectedVersion, cmd);
        return respond(updated, requestId(request), 200);
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String addressId,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        long expectedVersion = parseIfMatch(ifMatch);
        service.delete(principal.customerId(), parseAddressId(addressId), expectedVersion);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PutMapping("/{addressId}/default")
    public ResponseEntity<AddressResponseDto> setDefault(HttpServletRequest request, @PathVariable String addressId) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        AddressService.AddressView updated = service.setDefault(principal.customerId(), parseAddressId(addressId));
        return respond(updated, requestId(request), 200);
    }

    // ---------- response assembly ----------

    private ResponseEntity<AddressResponseDto> respond(AddressService.AddressView view, String requestId,
                                                        int status) {
        AddressResponseDto dto = present(view, requestId);
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, quotedEtag(view.version()))
                .body(dto);
    }

    private AddressResponseDto present(AddressService.AddressView view, String requestId) {
        AddressServiceabilityEvaluator.Result result = serviceability.evaluate(view.postalCode());
        observability.serviceabilityResult(result);
        return AddressResponseDto.from(view, result, requestId);
    }

    private static String quotedEtag(long version) {
        return "\"address-" + version + "\"";
    }

    // ---------- request parsing ----------

    private static AddressId parseAddressId(String raw) {
        try {
            return new AddressId(raw);
        } catch (IllegalArgumentException e) {
            // Malformed shape is treated identically to "unknown" -- no ownership/shape enumeration.
            throw new AddressFailure(AddressFailure.Reason.NOT_FOUND);
        }
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new AddressFailure(AddressFailure.Reason.PRECONDITION_REQUIRED);
        }
        String trimmed = ifMatch.trim();
        if (trimmed.length() > MAX_IF_MATCH_LENGTH) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        Matcher m = IF_MATCH_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
    }

    /** ABSENT if the key is not in the body at all; PRESENT(null) for an explicit JSON null;
     *  PRESENT(value) for a string value. A non-string, non-null value is a malformed shape. */
    private static PatchField<String> extract(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.has(field)) {
            return PatchField.absent();
        }
        JsonNode value = body.get(field);
        if (value.isNull()) {
            return PatchField.of(null);
        }
        if (!value.isTextual()) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return PatchField.of(value.textValue());
    }

    private static PatchField<Double> extractDouble(JsonNode body, String field) {
        if (body == null || !body.isObject() || !body.has(field)) {
            return PatchField.absent();
        }
        JsonNode value = body.get(field);
        if (value.isNull()) {
            return PatchField.of(null);
        }
        if (!value.isNumber()) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return PatchField.of(value.asDouble());
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
