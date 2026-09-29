package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR-13A — {@code /v1/customer/checkout}. CUSTOMER_AUTHENTICATED (CustomerAuthFilter already ran);
 * ownership is ALWAYS the verified principal. The body carries ONLY {@code addressId}; the cart
 * version arrives as {@code If-Match: "cart-<n>"} and the request identity as {@code Idempotency-Key}.
 * Any other body field (price, totals, customerId, routing, ...) is never read. Every response is
 * {@code no-store}. Failures are counted exactly once in {@link CheckoutExceptionHandler}; this class
 * records successes only.
 */
@RestController
@RequestMapping("/v1/customer/checkout")
public class CheckoutController {

    private static final int MAX_IF_MATCH_LENGTH = 40;
    private static final Pattern IF_MATCH = Pattern.compile("^\"?cart-([0-9]{1,15})\"?$");

    private final CheckoutService service;
    private final CheckoutObservability observability;

    public CheckoutController(CheckoutService service, CheckoutObservability observability) {
        this.service = service;
        this.observability = observability;
    }

    @PostMapping("/quote")
    public ResponseEntity<CheckoutQuoteDto> createQuote(
            HttpServletRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        long cartVersion = parseIfMatch(ifMatch);
        String key = requireIdempotencyKey(idempotencyKey);
        String addressId = parseAddressId(body);
        CheckoutQuote quote = service.createQuote(p.customerId(), cartVersion, key, addressId, requestId(request));
        observability.quoteSuccess();
        return ok(CheckoutQuoteDto.of(quote, requestId(request)));
    }

    @GetMapping("/quotes/{quoteId}")
    public ResponseEntity<CheckoutQuoteDto> readQuote(HttpServletRequest request, @PathVariable String quoteId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        CheckoutQuote quote = service.readQuote(p.customerId(), quoteId);
        observability.quoteReadSuccess();
        return ok(CheckoutQuoteDto.of(quote, requestId(request)));
    }

    private static ResponseEntity<CheckoutQuoteDto> ok(CheckoutQuoteDto dto) {
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, "no-store").body(dto);
    }

    // ---------- request parsing ----------

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new CheckoutFailure(CheckoutFailure.Reason.PRECONDITION_REQUIRED);
        }
        String trimmed = ifMatch.trim();
        if (trimmed.length() > MAX_IF_MATCH_LENGTH) {
            throw new CheckoutFailure(CheckoutFailure.Reason.INVALID_REQUEST);
        }
        Matcher m = IF_MATCH.matcher(trimmed);
        if (!m.matches()) {
            throw new CheckoutFailure(CheckoutFailure.Reason.INVALID_REQUEST);
        }
        return Long.parseLong(m.group(1));
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new CheckoutFailure(CheckoutFailure.Reason.IDEMPOTENCY_REQUIRED);
        }
        if (!CheckoutService.isValidIdempotencyKey(key)) {
            throw new CheckoutFailure(CheckoutFailure.Reason.INVALID_REQUEST);
        }
        return key;
    }

    /** Only {@code addressId} is read; it must be a string. Its FORMAT is judged as ownership (404). */
    private static String parseAddressId(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("addressId") || !body.get("addressId").isTextual()) {
            throw new CheckoutFailure(CheckoutFailure.Reason.INVALID_REQUEST);
        }
        return body.get("addressId").asText();
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
