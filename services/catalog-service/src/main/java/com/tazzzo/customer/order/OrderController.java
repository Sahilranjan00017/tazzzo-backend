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
import org.springframework.web.bind.annotation.RequestParam;
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

    private static final java.util.regex.Pattern SLOT_ID =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}~[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private final OrderService service;
    private final OrderHttpObservability observability;
    private final boolean slotRequired;
    private final OrderLifecycleService lifecycle;

    public OrderController(OrderService service, OrderLifecycleService lifecycle, OrderHttpObservability observability,
                           @org.springframework.beans.factory.annotation.Value("${tazzzo.checkout.delivery-slot-required:false}") boolean slotRequired) {
        this.service = service;
        this.lifecycle = lifecycle;
        this.observability = observability;
        this.slotRequired = slotRequired;
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.PlaceOrder.class)))
    @PostMapping
    public ResponseEntity<CustomerOrderDto> place(HttpServletRequest request,
                                                  @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        String quoteId = parseQuoteId(body);
        requireCod(body);
        String slotId = parseDeliverySlotId(body);
        if (slotId == null && slotRequired) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
        }
        Order order = service.placeCodOrder(p.customerId(), quoteId, slotId);
        return ok(CustomerOrderDto.of(order, requestId(request)));
    }

    /** The caller's own order history, newest first. Only {@code page_size} and {@code cursor} are accepted. */
    @GetMapping
    public ResponseEntity<CustomerOrderDto.Page> list(HttpServletRequest request,
                                                      @RequestParam(name = "page_size", required = false) String pageSize,
                                                      @RequestParam(name = "cursor", required = false) String cursor) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        for (String name : request.getParameterMap().keySet()) {
            if (!name.equals("page_size") && !name.equals("cursor")) {
                throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
            }
        }
        if (pageSize != null && !pageSize.matches("[1-9][0-9]{0,2}") || cursor != null && cursor.length() > 128) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
        }
        OrderLifecycleService.Page page = lifecycle.list(p.customerId(), cursor,
                pageSize == null ? OrderLifecycleService.DEFAULT_PAGE_SIZE : Integer.parseInt(pageSize));
        observability.readSuccess();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CustomerOrderDto.Page(page.orders().stream().map(CustomerOrderDto.Summary::of).toList(),
                        page.nextCursor(), requestId(request)));
    }

    /** Cancel one of the caller's OWN confirmed orders; body {@code {"reason": "CHANGED_MIND"|"ORDERED_BY_MISTAKE"|"OTHER"}}. */
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.CancelOrder.class)))
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CustomerOrderDto> cancel(HttpServletRequest request, @PathVariable String orderId,
                                                   @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        if (body == null || !body.isObject() || !body.has("reason") || !body.get("reason").isTextual()) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
        }
        Order order = lifecycle.cancelByCustomer(p.customerId(), orderId, body.get("reason").asText());
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

    /** Optional; when present it must be a string of the exact slot-id shape (its availability is judged by the domain). */
    private static String parseDeliverySlotId(JsonNode body) {
        if (!body.has("deliverySlotId")) {
            return null;
        }
        JsonNode n = body.get("deliverySlotId");
        if (!n.isTextual() || !SLOT_ID.matcher(n.asText()).matches()) {
            throw new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST);
        }
        return n.asText();
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
