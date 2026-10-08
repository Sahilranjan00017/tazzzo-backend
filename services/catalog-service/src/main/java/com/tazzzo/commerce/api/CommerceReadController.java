package com.tazzzo.commerce.api;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyService;
import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import com.tazzzo.catalog.ratelimit.InstallationIdResolver;
import com.tazzzo.catalog.ratelimit.TrustedCallerResolver;
import com.tazzzo.commerce.api.dto.NodeDetailDto;
import com.tazzzo.commerce.api.dto.NodeListResponse;
import com.tazzzo.commerce.api.dto.PagedProductResponse;
import com.tazzzo.commerce.api.dto.ProductDetailDto;
import com.tazzzo.commerce.api.dto.ServiceabilityResponseDto;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.read.CommerceListService;
import com.tazzzo.commerce.read.CommercePdpService;
import com.tazzzo.commerce.read.CommerceSearchService;
import com.tazzzo.commerce.read.CommerceServiceabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
    /** The taxonomy node id grammar (super-category, category, sub-category, vertical), as in docs/api/v1. */
    private static final Pattern NODE_ID = Pattern.compile("TZ[SCGV]-[0-9]{6}");

    private final ConsumerTaxonomyService taxonomy;
    private final CommerceListService list;
    private final CommercePdpService pdp;
    private final CommerceServiceabilityService serviceability;
    private final CommerceSearchService search;
    private final ClientIpResolver clientIps;
    private final ConsumerObservability observe;
    private final com.tazzzo.location.GeoPincodeResolver geo;
    private final TrustedCallerResolver trustedCallers;

    public CommerceReadController(ConsumerTaxonomyService taxonomy, CommerceListService list,
                                  CommercePdpService pdp, CommerceServiceabilityService serviceability,
                                  CommerceSearchService search,
                                  ClientIpResolver clientIps, ConsumerObservability observe,
                                  com.tazzzo.location.GeoPincodeResolver geo,
                                  TrustedCallerResolver trustedCallers) {
        this.taxonomy = taxonomy;
        this.list = list;
        this.pdp = pdp;
        this.serviceability = serviceability;
        this.search = search;
        this.clientIps = clientIps;
        this.observe = observe;
        this.geo = geo;
        this.trustedCallers = trustedCallers;
    }

    @GetMapping("/categories")
    public ResponseEntity<NodeListResponse> categories(
            @RequestParam(name = "release", required = false) String release,
            HttpServletRequest request) {
        return measured(ConsumerObservability.Route.COMMERCE_CATEGORIES, () -> {
            NodeListResponse body = RuntimeToDtoMapper.nodes(taxonomy.root(release, identity(request),
                    ConsumerObservability.Route.COMMERCE_CATEGORIES), requestId(request));
            return taxonomyResponse("categories", null, body, request);
        });
    }

    @GetMapping("/categories/{id}/children")
    public ResponseEntity<NodeListResponse> children(@PathVariable("id") String nodeId,
                                     @RequestParam(name = "release", required = false) String release,
                                     HttpServletRequest request) {
        return measured(ConsumerObservability.Route.COMMERCE_CHILDREN, () -> {
            NodeListResponse body = RuntimeToDtoMapper.nodes(taxonomy.children(nodeId, release, identity(request),
                    ConsumerObservability.Route.COMMERCE_CHILDREN), requestId(request));
            return taxonomyResponse("children", nodeId, body, request);
        });
    }

    /**
     * One consumer-visible taxonomy node by id, so a client can name a node (a grid tile, a deep
     * category page) without walking the tree. Same reachability, charge and PARENT probe as
     * CHILDREN with no candidates ({@link ConsumerTaxonomyService#node}): unknown, hidden and
     * consumer-empty are the same flat 404. A malformed id is this route's own 400, refused before
     * the release is resolved or anything is charged. Same public Cache-Control and content-hash
     * ETag as the sibling taxonomy reads, set only on success.
     */
    @GetMapping("/categories/{id}")
    public ResponseEntity<NodeDetailDto> category(@PathVariable("id") String nodeId,
                                                  @RequestParam(name = "release", required = false) String release,
                                                  HttpServletRequest request) {
        return measured(ConsumerObservability.Route.COMMERCE_NODE, () -> {
            if (!NODE_ID.matcher(nodeId).matches()) {
                throw new ConsumerFailures.InvalidRequest("malformed node id");
            }
            NodeListResponse single = RuntimeToDtoMapper.nodes(taxonomy.node(nodeId, release, identity(request),
                    ConsumerObservability.Route.COMMERCE_NODE), requestId(request));
            return conditional(TaxonomyETag.compute("node", nodeId, single), RuntimeToDtoMapper.node(single), request);
        });
    }

    /**
     * PR-10C — categories/children/node ONLY. Cache-Control is set here (never before the delegate
     * call succeeds — see PR-10C cache-safety note on {@link CommerceExceptionHandler}) so an error
     * can never inherit the public cache header. A deterministic content-hash {@code ETag}
     * ({@link TaxonomyETag}) is computed from the ACTUAL visible item set, never from
     * {@code requestId} or an internal database version; a matching {@code If-None-Match} yields a
     * bodiless {@code 304} carrying the SAME {@code ETag} and {@code Cache-Control}.
     */
    private ResponseEntity<NodeListResponse> taxonomyResponse(String route, String nodeId,
                                                              NodeListResponse body, HttpServletRequest request) {
        return conditional(TaxonomyETag.compute(route, nodeId, body), body, request);
    }

    /** The one public-cache + conditional-GET answer every taxonomy read shares. */
    private <B> ResponseEntity<B> conditional(String etag, B body, HttpServletRequest request) {
        if (TaxonomyETag.matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_PUBLIC)
                    .eTag(etag)
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_PUBLIC)
                .eTag(etag)
                .body(body);
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

    /** PR-G: public product search; PIN-only location like the list (lat/lng is 400 here too). */
    @GetMapping("/search")
    public PagedProductResponse search(@RequestParam(name = "q", required = false) String q,
                                       @RequestParam(name = "release", required = false) String release,
                                       @RequestParam(name = "page_size", required = false) String pageSize,
                                       @RequestParam(name = "cursor", required = false) String cursor,
                                       @RequestParam(name = "pin", required = false) String pin,
                                       @RequestParam(name = "lat", required = false) String lat,
                                       @RequestParam(name = "lng", required = false) String lng,
                                       HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_PRIVATE_NO_STORE);
        return measured(ConsumerObservability.Route.COMMERCE_SEARCH, () -> {
            LocationQuery location = CommerceLocationParser.parse(pin, lat, lng);
            return RuntimeToDtoMapper.page(
                    search.search(q, release, pageSize, cursor, location, identity(request)), requestId(request));
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
            // PIN path: validated before admission, exactly as before. Geo path: the provider is called only AFTER
            // admission is charged, so an unauthenticated flood cannot reach it ahead of the limiter.
            boolean viaGeo = CommerceLocationParser.usesGeo(geo, lat, lng);
            Pincode validPin = viaGeo ? null : CommerceLocationParser.requirePin(pin, lat, lng);
            return RuntimeToDtoMapper.serviceability(viaGeo
                            ? serviceability.resolve(() -> CommerceLocationParser.requirePin(pin, lat, lng, geo), identity(request))
                            : serviceability.resolve(validPin, identity(request)),
                    requestId(request));
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
        } catch (com.tazzzo.commerce.read.CommerceReadUnavailableException e) {
            // PR-10C: the ONE place a bounded, closed-vocabulary failure_class is recorded for a
            // domain-read/freshness 503 -- never the exception message, never the SKU/route detail.
            outcome = ConsumerObservability.Outcome.UNAVAILABLE;
            observe.failureClass(route, toFailureClass(e.category()));
            throw e;
        } catch (com.mongodb.MongoException e) {
            outcome = ConsumerObservability.Outcome.UNAVAILABLE;
            observe.failureClass(route, ConsumerObservability.FailureClass.MONGO);
            throw e;
        } catch (ConsumerFailures.Unavailable | com.tazzzo.commerce.read.ProductDetailCompositionException e) {
            // Generic infra/config faults (cursor key unconfigured, client IP unresolvable, a stale
            // PDP projection) with no more specific bounded class to report.
            outcome = ConsumerObservability.Outcome.UNAVAILABLE;
            throw e;
        } catch (RuntimeException e) {
            // Anything else is, by elimination, a genuinely unexpected programming failure (500) --
            // never reclassified as an infrastructure outage.
            outcome = ConsumerObservability.Outcome.INTERNAL_ERROR;
            observe.failureClass(route, ConsumerObservability.FailureClass.INTERNAL);
            throw e;
        } finally {
            observe.request(route, outcome, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private static ConsumerObservability.FailureClass toFailureClass(
            com.tazzzo.commerce.read.CommerceReadUnavailableException.Category category) {
        return switch (category) {
            case MONGO -> ConsumerObservability.FailureClass.MONGO;
            case PRICING -> ConsumerObservability.FailureClass.PRICING;
            case INVENTORY -> ConsumerObservability.FailureClass.INVENTORY;
            case MEDIA -> ConsumerObservability.FailureClass.MEDIA;
            case SERVICEABILITY -> ConsumerObservability.FailureClass.SERVICEABILITY;
            case FRESHNESS_NOT_READY -> ConsumerObservability.FailureClass.FRESHNESS_NOT_READY;
        };
    }

    private ConsumerIdentity identity(HttpServletRequest request) {
        return new ConsumerIdentity(clientIp(request),
                InstallationIdResolver.resolve(request.getHeader(InstallationIdResolver.HEADER)),
                trustedCallers.resolve(request.getHeader(TrustedCallerResolver.CALLER_HEADER),
                        request.getHeader(TrustedCallerResolver.SECRET_HEADER)));
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
