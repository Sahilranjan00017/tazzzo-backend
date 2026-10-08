package com.tazzzo.customer.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.commerce.contract.LocationQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR-12C — {@code /v1/customer/cart}. CUSTOMER_AUTHENTICATED (CustomerAuthFilter already ran);
 * ownership is ALWAYS the verified principal — no customerId/cartId in any URL, body or header. SKU
 * ids are resource identifiers, never ownership. Every response is {@code no-store} with
 * {@code ETag: "cart-<version>"}. Domain failures are counted exactly once in
 * {@link CartExceptionHandler}; this class records successes only.
 */
@RestController
@RequestMapping("/v1/customer/cart")
public class CartController {

    private static final int MAX_IF_MATCH_LENGTH = 40;
    private static final Pattern IF_MATCH = Pattern.compile("^\"?cart-([0-9]{1,15})\"?$");
    /**
     * The canonical SKU/product id grammar, the same as {@code ContentBlock.PRODUCT_ID} and the OpenAPI
     * ProductId: a superset of the earlier {@code ^TZP-[0-9]{1,18}$}, so ids such as {@code TZP-MED-3}
     * that the catalogue already creates (it requires only {@code ^TZP-}) can be carted. Only URL-unreserved characters, at most 44 in
     * all; the id is only ever compared by equality and stored as a value, never used as a key path.
     */
    private static final Pattern SKU = Pattern.compile("^TZP-[A-Za-z0-9-]{1,40}$");

    private final CartService service;
    private final CartEnricher enricher;
    private final CartLocationResolver locations;
    private final CartObservability observability;

    public CartController(CartService service, CartEnricher enricher, CartLocationResolver locations,
                          CartObservability observability) {
        this.service = service;
        this.enricher = enricher;
        this.locations = locations;
        this.observability = observability;
    }

    @GetMapping
    public ResponseEntity<CartResponseDto> get(HttpServletRequest request,
                                               @RequestParam(value = "addressId", required = false) String addressId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        LocationQuery location = locations.resolve(p.customerId(), addressId);
        CartState state = service.get(p.customerId());
        CartResponseDto dto = present(state, location, request);
        observability.readSuccess();
        return respond(dto);
    }

    @PutMapping("/items/{skuId}")
    public ResponseEntity<CartResponseDto> setItem(HttpServletRequest request, @PathVariable String skuId,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                   @RequestParam(value = "addressId", required = false) String addressId,
                                                   @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        long expected = parseIfMatch(ifMatch);
        String sku = parseSku(skuId);
        int quantity = parseQuantity(body);
        LocationQuery location = locations.resolve(p.customerId(), addressId);
        CartState state = service.setItem(p.customerId(), sku, quantity, expected);
        observability.mutationSuccess(CartObservability.Operation.SET_ITEM);
        return respond(present(state, location, request));
    }

    @DeleteMapping("/items/{skuId}")
    public ResponseEntity<CartResponseDto> removeItem(HttpServletRequest request, @PathVariable String skuId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      @RequestParam(value = "addressId", required = false) String addressId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        long expected = parseIfMatch(ifMatch);
        String sku = parseSku(skuId);
        LocationQuery location = locations.resolve(p.customerId(), addressId);
        CartState state = service.removeItem(p.customerId(), sku, expected);
        observability.mutationSuccess(CartObservability.Operation.REMOVE_ITEM);
        return respond(present(state, location, request));
    }

    @DeleteMapping
    public ResponseEntity<CartResponseDto> clear(HttpServletRequest request,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                 @RequestParam(value = "addressId", required = false) String addressId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        long expected = parseIfMatch(ifMatch);
        LocationQuery location = locations.resolve(p.customerId(), addressId);
        CartState state = service.clear(p.customerId(), expected);
        observability.mutationSuccess(CartObservability.Operation.CLEAR);
        return respond(present(state, location, request));
    }

    // ---------- response ----------

    private CartResponseDto present(CartState state, LocationQuery location, HttpServletRequest request) {
        CartResponseDto dto = enricher.present(state, location, requestId(request));
        dto.items().forEach(i -> i.issues().forEach(observability::itemIssue));
        return dto;
    }

    private static ResponseEntity<CartResponseDto> respond(CartResponseDto dto) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, "\"cart-" + dto.version() + "\"")
                .body(dto);
    }

    // ---------- request parsing ----------

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new CartFailure(CartFailure.Reason.PRECONDITION_REQUIRED);
        }
        String trimmed = ifMatch.trim();
        if (trimmed.length() > MAX_IF_MATCH_LENGTH) {
            throw new CartFailure(CartFailure.Reason.INVALID_REQUEST);
        }
        Matcher m = IF_MATCH.matcher(trimmed);
        if (!m.matches()) {
            throw new CartFailure(CartFailure.Reason.INVALID_REQUEST);
        }
        return Long.parseLong(m.group(1));
    }

    /** A malformed SKU is indistinguishable from an unknown one (no grammar/existence oracle). */
    private static String parseSku(String raw) {
        if (raw == null || !SKU.matcher(raw).matches()) {
            throw new CartFailure(CartFailure.Reason.NOT_FOUND);
        }
        return raw;
    }

    /** Only {@code quantity} is read; any other body field (price, title, stock, ...) is ignored, never stored. */
    private static int parseQuantity(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("quantity")) {
            throw new CartFailure(CartFailure.Reason.INVALID_REQUEST);
        }
        JsonNode q = body.get("quantity");
        if (!q.isIntegralNumber() || !q.canConvertToInt()) {
            throw new CartFailure(CartFailure.Reason.INVALID_REQUEST);
        }
        return q.intValue();
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
