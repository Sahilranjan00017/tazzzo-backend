package com.tazzzo.commerce.api;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyService;
import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import com.tazzzo.catalog.ratelimit.InstallationIdResolver;
import com.tazzzo.commerce.api.dto.NodeListResponse;
import com.tazzzo.commerce.api.dto.PagedProductResponse;
import com.tazzzo.commerce.api.dto.ProductDetailDto;
import com.tazzzo.commerce.api.dto.ServiceabilityResponseDto;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.read.CommerceListService;
import com.tazzzo.commerce.read.CommercePdpService;
import com.tazzzo.commerce.read.CommerceServiceabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The public customer-facing commerce read transport (PR-10B). Q4-style transport ONLY: it parses
 * and validates request shape, resolves rate-limit identity, delegates to the reused consumer
 * taxonomy service (categories/children) and the commerce read services (list/PDP/serviceability),
 * maps to the frozen DTOs, and sets cache headers. It performs NO Mongo query, NO domain rule, NO
 * discount/buyable/eligibility logic — all of that lives behind the delegated services.
 *
 * <p>{@code /v1} is exposed as PUBLIC_CONSUMER by {@code SurfaceClassifier}; the auth filter bypasses
 * it. {@code page_size} / {@code cursor} / {@code pin} / {@code lat} / {@code lng} arrive as RAW
 * strings so a malformed value becomes this route's own typed 400 inside the measured boundary,
 * never a pre-boundary framework error. Every response carries the server-authoritative requestId
 * from {@code RequestIdFilter}.
 */
@RestController
@RequestMapping("/v1")
public class CommerceReadController {

    private static final String CACHE_PUBLIC = "public, max-age=300, stale-while-revalidate=60";
    private static final String CACHE_PRIVATE_NO_STORE = "private, no-store";
    private static final String REQUEST_ID_ATTR = com.tazzzo.catalog.api.RequestIdFilter.REQUEST_ID;

    private final ConsumerTaxonomyService taxonomy;
    private final CommerceListService list;
    private final CommercePdpService pdp;
    private final CommerceServiceabilityService serviceability;
    private final ClientIpResolver clientIps;
    private final ConsumerObservability observe;

    public CommerceReadController(ConsumerTaxonomyService taxonomy, CommerceListService list,
                                  CommercePdpService pdp, CommerceServiceabilityService serviceability,
                                  ClientIpResolver clientIps, ConsumerObservability observe) {
        this.taxonomy = taxonomy;
        this.list = list;
        this.pdp = pdp;
        this.serviceability = serviceability;
        this.clientIps = clientIps;
        this.observe = observe;
    }

    @GetMapping("/categories")
    public NodeListResponse categories(@RequestParam(name = "release", required = false) String release,
                                       HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_PUBLIC);
        return measured(ConsumerObservability.Route.COMMERCE_CATEGORIES, () ->
                RuntimeToDtoMapper.nodes(taxonomy.root(release, identity(request),
                        ConsumerObservability.Route.COMMERCE_CATEGORIES), requestId(request)));
    }

    @GetMapping("/categories/{id}/children")
    public NodeListResponse children(@PathVariable("id") String nodeId,
                                     @RequestParam(name = "release", required = false) String release,
                                     HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_PUBLIC);
        return measured(ConsumerObservability.Route.COMMERCE_CHILDREN, () ->
                RuntimeToDtoMapper.nodes(taxonomy.children(nodeId, release, identity(request),
                        ConsumerObservability.Route.COMMERCE_CHILDREN), requestId(request)));
    }

    @GetMapping("/categories/{id}/products")
    public PagedProductResponse products(@PathVariable("id") String nodeId,
                                         @RequestParam(name = "release", required = false) String release,
                                         @RequestParam(name = "page_size", required = false) String pageSize,
                                         @RequestParam(name = "cursor", required = false) String cursor,
                                         @RequestParam(name = "pin", required = false) String pin,
                                         @RequestParam(name = "lat", required = false) String lat,
                                         @RequestParam(name = "lng", required = false) String lng,
                                         HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_PRIVATE_NO_STORE);
        return measured(ConsumerObservability.Route.COMMERCE_LIST, () -> {
            LocationQuery location = CommerceLocationParser.parse(pin, lat, lng);
            return RuntimeToDtoMapper.page(
                    list.list(nodeId, release, pageSize, cursor, location, identity(request)), requestId(request));
        });
    }

    @GetMapping("/products/{id}")
    public ProductDetailDto productDetail(@PathVariable("id") String productId,
                                          @RequestParam(name = "release", required = false) String release,
                                          @RequestParam(name = "pin", required = false) String pin,
                                          @RequestParam(name = "lat", required = false) String lat,
                                          @RequestParam(name = "lng", required = false) String lng,
                                          HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_PRIVATE_NO_STORE);
        return measured(ConsumerObservability.Route.COMMERCE_PDP, () -> {
            LocationQuery location = CommerceLocationParser.parse(pin, lat, lng);
            CommercePdpService.Result result = pdp.detail(productId, release, location, identity(request));
            if (result.status() != CommercePdpService.Status.FOUND) {
                // NOT_FOUND and INELIGIBLE collapse to the SAME flat 404 (rule L-5).
                throw new ConsumerFailures.NotFound("product not found: " + productId);
            }
            return RuntimeToDtoMapper.detail(result.detail(), result.resolvedReleaseId(), requestId(request));
        });
    }

    @GetMapping("/serviceability")
    public ServiceabilityResponseDto serviceability(@RequestParam(name = "pin", required = false) String pin,
                                                    @RequestParam(name = "lat", required = false) String lat,
                                                    @RequestParam(name = "lng", required = false) String lng,
                                                    HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_PRIVATE_NO_STORE);
        return measured(ConsumerObservability.Route.COMMERCE_SERVICEABILITY, () -> {
            Pincode validPin = CommerceLocationParser.requirePin(pin, lat, lng);
            return RuntimeToDtoMapper.serviceability(
                    serviceability.resolve(validPin, identity(request)), requestId(request));
        });
    }

    /** The ONE request-clock boundary; outcome derived from the typed failure that escapes. */
    private <T> T measured(ConsumerObservability.Route route, Supplier<T> call) {
        long started = System.nanoTime();
        ConsumerObservability.Outcome outcome = ConsumerObservability.Outcome.UNAVAILABLE;
        try {
            T response = call.get();
            outcome = ConsumerObservability.Outcome.SUCCESS;
            return response;
        } catch (ConsumerFailures.NotFound e) {
            outcome = ConsumerObservability.Outcome.NOT_FOUND;
            throw e;
        } catch (ConsumerFailures.RateLimited e) {
            outcome = ConsumerObservability.Outcome.RATE_LIMITED;
            throw e;
        } catch (ConsumerFailures.InvalidRequest e) {
            outcome = ConsumerObservability.Outcome.INVALID_REQUEST;
            throw e;
        } catch (ConsumerFailures.InvalidCursor e) {
            outcome = ConsumerObservability.Outcome.INVALID_CURSOR;
            throw e;
        } finally {
            observe.request(route, outcome, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private ConsumerIdentity identity(HttpServletRequest request) {
        return new ConsumerIdentity(clientIp(request),
                InstallationIdResolver.resolve(request.getHeader(InstallationIdResolver.HEADER)));
    }

    private String clientIp(HttpServletRequest request) {
        try {
            return clientIps.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
        } catch (ClientIpUnresolvableException e) {
            throw new ConsumerFailures.Unavailable("client identity unresolvable");
        }
    }

    /**
     * PR-10B final review #7 — {@code RequestIdFilter} is mandatory and always runs first
     * ({@code @Order(HIGHEST_PRECEDENCE)}), so this attribute is always populated in production; if
     * it is ever absent, fail fast rather than silently return the literal string {@code "null"} as
     * though it were a real, valid server-authoritative request id.
     */
    static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTR);
        if (!(value instanceof String id) || id.isBlank()) {
            throw new IllegalStateException("request id not populated by RequestIdFilter");
        }
        return id;
    }
}
