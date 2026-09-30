package com.tazzzo.customer.order;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR-15A-2 — {@code /v1/customer/orders}, the FIRST customer-reachable Order surface. CUSTOMER_AUTHENTICATED
 * ({@code CustomerAuthFilter} already ran); the customer is ALWAYS the verified principal — no
 * {@code customerId} is read from the body, path, query or headers.
 *
 * <p><b>POST</b> delegates to {@link OrderService#placeCodOrder} and nothing else. The body's whole
 * authority is {@code quoteId} + {@code paymentMethod = "COD"}; every other field (status, payment
 * condition, reservation, inventory location, customer, price ...) is never read, following the
 * existing customer-controller convention of ignoring unknown fields. There is no {@code Idempotency-Key}:
 * {@code (customerId, quoteId)} is already structurally unique, so a same-quote replay returns the same
 * {@code CONFIRMED} Order. Always {@code 200} (first placement and replay alike, matching every other
 * customer create endpoint; the response deliberately does not reveal who the creator was).
 *
 * <p><b>GET</b> is an owned read of the stored snapshot ({@link OrderService#getOrder}); a malformed,
 * unknown or foreign id is the identical 404.
 *
 * <p>Every response is {@code no-store}. This controller depends on {@link OrderService} only — never on
 * the repository, Inventory, Cart, Pricing or Serviceability — and never calls the internal create-only
 * path (both enforced by {@code ModuleBoundaryTest}). Failures are mapped, and request-level metrics
 * recorded, in {@link OrderExceptionHandler}; the domain owns the placement metrics.
 */
@RestController
@RequestMapping("/v1/customer/orders")
public class OrderController {

    private final OrderService service;
    private final OrderHttpObservability observability;

    public OrderController(OrderService service, OrderHttpObservability observability) {
        this.service = service;
        this.observability = observability;
    }

    @PostMapping
    public ResponseEntity<CustomerOrderDto> place(HttpServletRequest request,
                                                  @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        String quoteId = parseQuoteId(body);
        requireCod(body);
        Order order = service.placeCodOrder(p.customerId(), quoteId);
        return ok(CustomerOrderDto.of(order, requestId(request)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<CustomerOrderDto> read(HttpServletRequest request, @PathVariable String orderId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        Order order = service.getOrder(p.customerId(), orderId);
        CustomerOrderDto dto = CustomerOrderDto.of(order, requestId(request));
        observability.readSuccess(); // only AFTER the durable read and a successful render
        return ok(dto);
    }

    private static ResponseEntity<CustomerOrderDto> ok(CustomerOrderDto dto) {
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, "no-store").body(dto);
    }

    // ---------- request parsing ----------

    /** Must be a string; its FORMAT is judged by the domain (a malformed id is the same 404 as an unknown one). */
    private static String parseQuoteId(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("quoteId") || !body.get("quoteId").isTextual()) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
        }
        return body.get("quoteId").asText();
    }

    /** Required, and exactly {@code "COD"} — the only payment method that exists. */
    private static void requireCod(JsonNode body) {
        if (!body.has("paymentMethod") || !body.get("paymentMethod").isTextual()) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
        }
        if (!PaymentMethod.COD.name().equals(body.get("paymentMethod").asText())) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.PAYMENT_METHOD_UNSUPPORTED);
        }
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
