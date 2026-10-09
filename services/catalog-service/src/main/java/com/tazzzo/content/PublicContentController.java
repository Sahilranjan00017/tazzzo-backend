package com.tazzzo.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tazzzo.catalog.api.ClientRequestErrors;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.consumer.ConsumerAdmissionGate;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import com.tazzzo.catalog.ratelimit.InstallationIdResolver;
import com.tazzzo.catalog.ratelimit.TrustedCallerResolver;
import com.tazzzo.media.MediaUrlResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

/**
 * Public reads: {@code GET /v1/content/home} (the live HOME blocks), {@code GET /v1/content/faqs} (the live help-centre FAQ,
 * optionally one {@code category}) and {@code GET /v1/app-config} (store open, maintenance, force-update versions, support
 * contacts, legal links). Admission is charged like every public read; the answers are short-lived cacheable
 * ({@code public, max-age=60}) because they are the same for every caller. Any other query parameter is refused.
 */
@RestController
public class PublicContentController {

    private static final Logger log = LoggerFactory.getLogger(PublicContentController.class);
    static final String CACHE = "public, max-age=60";

    /**
     * BANNER: {@code imageUrl} (every client), optional {@code desktopImageUrl} (a wide image for desktop web; absent = use
     * {@code imageUrl}), optional {@code subtitle}, and {@code altText} (always present: the editor's text, else the title).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Block(String blockId, String type, String title, String subtitle, String altText, String imageUrl, String desktopImageUrl,
                 String link, List<String> ids) { }

    record Home(List<Block> blocks, String requestId) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Versions(String minSupported, String latest) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Maintenance(boolean enabled, String message) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Support(String phone, String email) { }

    /** Approved legal documents as https links; a null link means "not published yet" (the app hides it). */
    record Legal(String termsUrl, String privacyUrl, String refundPolicyUrl) { }

    record Config(boolean storeOpen, Maintenance maintenance, Versions android, Versions ios, Support support, Legal legal,
                  String requestId) { }

    record Faq(String faqId, String category, String question, String answer) { }

    record Faqs(List<Faq> faqs, String requestId) { }

    private final ContentService content;
    private final MediaUrlResolver urls;
    private final ConsumerAdmissionGate gate;
    private final ClientIpResolver clientIps;
    private final TrustedCallerResolver trustedCallers;

    public PublicContentController(ContentService content, MediaUrlResolver urls, ConsumerAdmissionGate gate, ClientIpResolver clientIps,
                                   TrustedCallerResolver trustedCallers) {
        this.content = content;
        this.urls = urls;
        this.gate = gate;
        this.clientIps = clientIps;
        this.trustedCallers = trustedCallers;
    }

    @GetMapping("/v1/content/home")
    public ResponseEntity<Home> home(HttpServletRequest request) {
        ContentBlock.Channel channel = onlyChannel(request);
        gate.charge(ConsumerObservability.Route.CONTENT_HOME, identity(request), 1);
        List<Block> out = new ArrayList<>();
        int skipped = 0;
        for (ContentBlock b : content.live(ContentBlock.Placement.HOME, channel)) {
            if (b.type() == ContentBlock.Type.BANNER) {
                if (!urls.isConfigured()) {
                    skipped++;   // a banner without a resolvable image is dropped, never shown broken
                    continue;
                }
                ContentBlock.Payload p = b.payload();
                out.add(new Block(b.blockId(), b.type().name(), b.title(), p.subtitle(), ContentAdminController.altOrTitle(b),
                        urls.resolve(p.imageAssetKey()), p.desktopImageAssetKey() == null ? null : urls.resolve(p.desktopImageAssetKey()),
                        p.link(), null));
            } else {
                out.add(new Block(b.blockId(), b.type().name(), b.title(), null, null, null, null, null, b.payload().ids()));
            }
        }
        if (skipped > 0) log.warn("content_home_banners_skipped count={} reason=media_base_unconfigured", skipped);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE).body(new Home(out, requestId(request)));
    }

    @GetMapping("/v1/content/faqs")
    public ResponseEntity<Faqs> faqs(HttpServletRequest request) {
        ContentBlock.FaqCategory category = onlyCategory(request);
        gate.charge(ConsumerObservability.Route.CONTENT_FAQS, identity(request), 1);
        List<Faq> out = new ArrayList<>();
        for (ContentBlock b : content.liveFaqs(category)) {
            out.add(new Faq(b.blockId(), b.payload().faqCategory(), b.payload().question(), b.payload().answer()));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE).body(new Faqs(out, requestId(request)));
    }

    /**
     * Exactly one optional parameter, {@code channel} ({@code app} | {@code web}), given once; anything else is a 400. Absent
     * means an unidentified platform, which sees only content published to BOTH (multichannel D1/D2).
     */
    private static ContentBlock.Channel onlyChannel(HttpServletRequest request) {
        java.util.Map<String, String[]> params = request.getParameterMap();
        if (params.isEmpty()) return null;
        String[] values = params.get("channel");
        if (params.size() != 1 || values == null || values.length != 1) throw new ConsumerFailures.InvalidRequest("only channel is accepted");
        try {
            return ContentBlock.Channel.parse(values[0]);
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.InvalidRequest("channel must be app or web");
        }
    }

    /** Exactly one optional parameter, {@code category}, given once, from the closed set; anything else is a 400. */
    private static ContentBlock.FaqCategory onlyCategory(HttpServletRequest request) {
        java.util.Map<String, String[]> params = request.getParameterMap();
        if (params.isEmpty()) return null;
        String[] values = params.get("category");
        if (params.size() != 1 || values == null || values.length != 1) throw new ConsumerFailures.InvalidRequest("only category is accepted");
        try {
            return ContentBlock.faqCategory(values[0]);
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.InvalidRequest("unknown category");
        }
    }

    @GetMapping("/v1/app-config")
    public ResponseEntity<Config> appConfig(HttpServletRequest request) {
        refuseQuery(request);
        gate.charge(ConsumerObservability.Route.APP_CONFIG, identity(request), 1);
        AppConfig c = content.appConfig();
        Config body = new Config(c.storeOpen(), new Maintenance(c.maintenance(), c.maintenance() ? c.maintenanceMessage() : null),
                new Versions(c.minAndroid(), c.latestAndroid()), new Versions(c.minIos(), c.latestIos()),
                new Support(c.supportPhone(), c.supportEmail()), new Legal(c.termsUrl(), c.privacyUrl(), c.refundPolicyUrl()),
                requestId(request));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE).body(body);
    }

    private static void refuseQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new ConsumerFailures.InvalidRequest("no parameters accepted");
    }

    private ConsumerIdentity identity(HttpServletRequest request) {
        try {
            return new ConsumerIdentity(clientIps.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For")),
                    InstallationIdResolver.resolve(request.getHeader(InstallationIdResolver.HEADER)),
                    trustedCallers.resolve(request.getHeader(TrustedCallerResolver.CALLER_HEADER),
                            request.getHeader(TrustedCallerResolver.SECRET_HEADER)));
        } catch (ClientIpUnresolvableException e) {
            throw new ConsumerFailures.Unavailable("client identity unresolvable");
        }
    }

    static String requestId(HttpServletRequest request) {
        Object v = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return v == null ? "unknown" : v.toString();
    }

    record PublicError(String code, String message, String requestId, boolean retryable) { }

    /** Public envelope for this controller: generic messages, never cached. */
    @RestControllerAdvice(assignableTypes = PublicContentController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Errors {
        @ExceptionHandler(ConsumerFailures.InvalidRequest.class)
        ResponseEntity<PublicError> invalid(HttpServletRequest req) {
            return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", false, req, null);
        }

        @ExceptionHandler(ConsumerFailures.RateLimited.class)
        ResponseEntity<PublicError> limited(ConsumerFailures.RateLimited e, HttpServletRequest req) {
            return body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "too many requests", true, req,
                    Long.toString(Math.max(1, (e.retryAfter().toMillis() + 999) / 1000)));
        }

        @ExceptionHandler({ConsumerFailures.Unavailable.class, com.mongodb.MongoException.class})
        ResponseEntity<PublicError> unavailable(RuntimeException e, HttpServletRequest req) {
            log.warn("content_public_unavailable type={} request_id={}", e.getClass().getSimpleName(), requestId(req));
            return body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", true, req, null);
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<PublicError> internal(Exception e, HttpServletRequest req) {
            ClientRequestErrors.Kind kind = ClientRequestErrors.classify(e);
            if (kind != null) {
                log.warn("content_public_rejected reason={} request_id={}", kind, requestId(req));
                return body(kind.status(), "INVALID_REQUEST", kind.message(), false, req, null);
            }
            log.error("content_public_internal type={} request_id={}", e.getClass().getSimpleName(), requestId(req));
            return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", false, req, null);
        }

        private static ResponseEntity<PublicError> body(HttpStatus s, String code, String msg, boolean retryable, HttpServletRequest req,
                                                        String retryAfter) {
            ResponseEntity.BodyBuilder b = ResponseEntity.status(s).contentType(MediaType.APPLICATION_JSON) // never negotiated by Accept
                    .header(HttpHeaders.CACHE_CONTROL, "no-store");
            if (retryAfter != null) b = b.header(HttpHeaders.RETRY_AFTER, retryAfter);
            return b.body(new PublicError(code, msg, requestId(req), retryable));
        }
    }
}
