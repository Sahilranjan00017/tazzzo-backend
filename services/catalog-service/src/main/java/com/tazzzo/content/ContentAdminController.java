package com.tazzzo.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.media.InvalidMediaException;
import com.tazzzo.media.MediaStorage;
import com.tazzzo.media.MediaUploadPolicy;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.media.UploadTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INTERNAL admin transport for content blocks and the app config (legacy coarse rules: cms-writer writes, reader reads).
 * Every rule lives in {@link ContentService}; the audit actor is the authenticated principal, never a body field.
 */
@RestController
public class ContentAdminController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PayloadDto(String imageAssetKey, String link,
                      @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @io.swagger.v3.oas.annotations.media.Schema(pattern = com.tazzzo.catalog.domain.ProductIds.REGEX)) List<String> ids, String faqCategory, String question, String answer,
                      String subtitle, String altText, String desktopImageAssetKey,
                      String legalSlug, String body, String effectiveDate) { }

    /** {@code audience}: APP_ONLY | WEB_ONLY | BOTH; absent on create = BOTH, absent on update = unchanged. */
    record BlockRequest(String placement, String type, String title, Integer sort, String startsAt, String endsAt, PayloadDto payload,
                        Long expectedVersion, String audience) { }

    record StatusRequest(String to, Long expectedVersion) { }

    /**
     * {@code effectiveStatus} (DRAFT | SCHEDULED | LIVE | EXPIRED | ARCHIVED) is derived at read time from status and window;
     * {@code imageUrl}/{@code desktopImageUrl} are the resolved public URLs (absent while no media base is configured), so the
     * CMS previews exactly what clients will load. {@code createdBy}/{@code updatedBy} are actor ids (absent on older blocks).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BlockResponse(String blockId, String placement, String type, String title, int sort, String status, String effectiveStatus,
                         String startsAt, String endsAt, PayloadDto payload, String audience, String imageUrl, String desktopImageUrl,
                         long version, String createdAt, String updatedAt, String createdBy, String updatedBy) { }

    record BlockList(List<BlockResponse> items) { }

    record OrderItem(String blockId, Long expectedVersion) { }

    record ReorderRequest(String placement, List<OrderItem> order) { }

    record ContentUploadRequest(String contentType, Long sizeBytes) { }

    record ContentUploadResponse(String assetKey, String method, String url, Map<String, String> headers, String expiresAt, long maxBytes) { }

    /** Exactly the public {@code /v1/content/home} block shape, plus the editor's view of each block's state. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PreviewBlock(String blockId, String type, String title, String subtitle, String altText, String imageUrl, String desktopImageUrl,
                        String link, List<String> ids, String status, String effectiveStatus, String audience) { }

    record Preview(String channel, String at, boolean includeDrafts, List<PreviewBlock> blocks) { }

    record ConfigRequest(Boolean storeOpen, Boolean maintenance, String maintenanceMessage, String minAndroid, String latestAndroid,
                         String minIos, String latestIos, String supportPhone, String supportEmail, String termsUrl, String privacyUrl,
                         String refundPolicyUrl, Long expectedVersion) { }

    private final ContentService content;
    private final MediaStorage storage;
    private final MediaUploadPolicy uploads;
    private final MediaUrlResolver urls;
    private final Clock clock;

    public ContentAdminController(ContentService content, MediaStorage storage, MediaUploadPolicy uploads, MediaUrlResolver urls, Clock clock) {
        this.content = content;
        this.storage = storage;
        this.uploads = uploads;
        this.urls = urls;
        this.clock = clock;
    }

    private BlockResponse view(ContentBlock b) {
        ContentBlock.Payload p = b.payload();
        return new BlockResponse(b.blockId(), b.placement().name(), b.type().name(), b.title(), b.sort(), b.status().name(),
                b.effectiveAt(clock.instant()).name(),
                b.startsAt() == null ? null : b.startsAt().toString(), b.endsAt() == null ? null : b.endsAt().toString(),
                new PayloadDto(p.imageAssetKey(), p.link(), p.ids().isEmpty() ? null : p.ids(), p.faqCategory(), p.question(), p.answer(),
                        p.subtitle(), p.altText(), p.desktopImageAssetKey(), p.legalSlug(), p.body(), p.effectiveDate()),
                b.audience().name(), url(p.imageAssetKey()), url(p.desktopImageAssetKey()),
                b.version(), b.createdAt().toString(), b.updatedAt().toString(), b.createdBy(), b.updatedBy());
    }

    private String url(String key) {
        return key == null || !urls.isConfigured() ? null : urls.resolve(key);
    }

    @GetMapping("/api/v1/admin/content/blocks")
    public BlockList list(@RequestParam(name = "placement", defaultValue = "HOME") String placement,
                          @RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "audience", required = false) String audience) {
        return new BlockList(content.list(placement, status, audience).stream().map(this::view).toList());
    }

    @GetMapping("/api/v1/admin/content/blocks/{id}")
    public BlockResponse get(@PathVariable String id) {
        return view(content.get(id));
    }

    @PostMapping("/api/v1/admin/content/blocks")
    public ResponseEntity<BlockResponse> create(@RequestBody BlockRequest body, HttpServletRequest request) {
        if (body == null || body.sort() == null || body.payload() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "sort and payload are required");
        ContentBlock b = content.create(AdminActors.require(request), body.placement() == null ? "HOME" : body.placement(), body.type(),
                body.title(), body.sort(), instant(body.startsAt()), instant(body.endsAt()), payload(body.payload()), body.audience());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(b));
    }

    @PutMapping("/api/v1/admin/content/blocks/{id}")
    public BlockResponse update(@PathVariable String id, @RequestBody BlockRequest body, HttpServletRequest request) {
        if (body == null || body.sort() == null || body.payload() == null || body.expectedVersion() == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, "sort, payload and expectedVersion are required");
        }
        if (body.type() != null || body.placement() != null) throw new ContentFailure(ContentFailure.Reason.INVALID, "type and placement are fixed at creation");
        return view(content.update(AdminActors.require(request), id, body.expectedVersion(), body.title(), body.sort(),
                instant(body.startsAt()), instant(body.endsAt()), payload(body.payload()), body.audience()));
    }

    @PostMapping("/api/v1/admin/content/blocks/{id}/status")
    public BlockResponse status(@PathVariable String id, @RequestBody StatusRequest body, HttpServletRequest request) {
        if (body == null || body.expectedVersion() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "expectedVersion is required");
        return view(content.setStatus(AdminActors.require(request), id, body.expectedVersion(), body.to()));
    }

    /**
     * A direct-to-storage upload target for one banner image: a server-generated key under {@code c/home/}, bound to the
     * declared type and size. Reference the returned {@code assetKey} from a BANNER's {@code imageAssetKey} or
     * {@code desktopImageAssetKey}; the bytes are verified when the block is saved.
     */
    @PostMapping("/api/v1/admin/content/uploads")
    public ResponseEntity<ContentUploadResponse> upload(@RequestBody ContentUploadRequest body) {
        if (body == null || body.sizeBytes() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "contentType and sizeBytes are required");
        try {
            uploads.requireAcceptable(body.contentType(), body.sizeBytes());
        } catch (InvalidMediaException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
        }
        if (!storage.enabled()) throw new ContentFailure(ContentFailure.Reason.STORAGE_UNAVAILABLE, "media storage is not configured");
        String key = uploads.newContentKey(ContentService.CONTENT_KEY_PREFIX, body.contentType());
        UploadTarget t = storage.createUpload(key, body.contentType(), body.sizeBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ContentUploadResponse(key, t.method(), t.url(), t.headers(),
                t.expiresAt().toString(), uploads.maxBytes()));
    }

    /** Re-sequence a placement: {@code order} names every non-archived block once, each with the version the editor saw. */
    @PostMapping("/api/v1/admin/content/blocks/reorder")
    public BlockList reorder(@RequestBody ReorderRequest body, HttpServletRequest request) {
        if (body == null || body.order() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "order is required");
        List<ContentService.OrderEntry> order = body.order().stream().map(i -> {
            if (i == null || i.expectedVersion() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "each entry needs blockId and expectedVersion");
            return new ContentService.OrderEntry(i.blockId(), i.expectedVersion());
        }).toList();
        return new BlockList(content.reorder(AdminActors.require(request), body.placement() == null ? "HOME" : body.placement(), order)
                .stream().map(this::view).toList());
    }

    /**
     * What a channel's Home would show at {@code at} (default now), optionally with drafts as if published. Admin-only and
     * read-only: it never publishes, and drafts never reach a public endpoint.
     */
    @GetMapping("/api/v1/admin/content/preview/home")
    public Preview previewHome(@RequestParam(name = "channel") String channel, @RequestParam(name = "at", required = false) String at,
                               @RequestParam(name = "drafts", defaultValue = "false") boolean drafts) {
        ContentBlock.Channel ch;
        try {
            ch = ContentBlock.Channel.parse(channel);
        } catch (IllegalArgumentException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
        }
        Instant when = at == null ? clock.instant() : instant(at);
        List<PreviewBlock> out = new java.util.ArrayList<>();
        for (ContentBlock b : content.preview(ContentBlock.Placement.HOME, ch, when, drafts)) {
            ContentBlock.Payload p = b.payload();
            boolean banner = b.type() == ContentBlock.Type.BANNER;
            if (banner && !urls.isConfigured()) continue;   // the public endpoint drops it too
            out.add(new PreviewBlock(b.blockId(), b.type().name(), b.title(), p.subtitle(), banner ? altOrTitle(b) : null,
                    url(p.imageAssetKey()), url(p.desktopImageAssetKey()),
                    p.link(), p.ids().isEmpty() ? null : p.ids(), b.status().name(), b.effectiveAt(when).name(), b.audience().name()));
        }
        return new Preview(channel, when.toString(), drafts, out);
    }

    static String altOrTitle(ContentBlock b) {
        return b.payload().altText() != null ? b.payload().altText() : b.title();
    }

    @GetMapping("/api/v1/admin/app-config")
    public AppConfig getConfig() {
        return content.appConfig();
    }

    @PutMapping("/api/v1/admin/app-config")
    public AppConfig putConfig(@RequestBody ConfigRequest body, HttpServletRequest request) {
        if (body == null || body.expectedVersion() == null || body.storeOpen() == null || body.maintenance() == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, "storeOpen, maintenance and expectedVersion are required");
        }
        return content.putAppConfig(AdminActors.require(request), body.expectedVersion(), new AppConfig(body.storeOpen(), body.maintenance(),
                body.maintenanceMessage(), body.minAndroid(), body.latestAndroid(), body.minIos(), body.latestIos(), body.supportPhone(),
                body.supportEmail(), body.termsUrl(), body.privacyUrl(), body.refundPolicyUrl(), body.expectedVersion()));
    }

    private static Instant instant(String raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, "times are ISO-8601 instants");
        }
    }

    private static ContentBlock.Payload payload(PayloadDto p) {
        return new ContentBlock.Payload(p.imageAssetKey(), p.link(), p.ids(), p.faqCategory(), p.question(), p.answer(),
                p.subtitle(), p.altText(), p.desktopImageAssetKey(), p.legalSlug(), p.body(), p.effectiveDate());
    }

    /** The admin envelope for this controller only. */
    @RestControllerAdvice(assignableTypes = ContentAdminController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Errors {
        @ExceptionHandler(ContentFailure.class)
        ResponseEntity<ErrorBody> failure(ContentFailure e, HttpServletRequest req) {
            HttpStatus s = switch (e.reason()) {
                case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case STALE_VERSION, STATE_CONFLICT -> HttpStatus.CONFLICT;
                case STORAGE_UNAVAILABLE, STORAGE_OUTAGE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            String code = switch (e.reason()) {
                case INVALID -> "INVALID_CONTENT";
                case NOT_FOUND -> "NOT_FOUND";
                case STALE_VERSION -> "STALE_VERSION";
                case STATE_CONFLICT -> "STATE_CONFLICT";
                case STORAGE_UNAVAILABLE -> "MEDIA_STORAGE_NOT_CONFIGURED";
                case STORAGE_OUTAGE -> "MEDIA_STORAGE_UNAVAILABLE";
            };
            Map<String, String> b = new LinkedHashMap<>();
            b.put("code", code);
            b.put("message", e.getMessage());
            b.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
            return ResponseEntity.status(s).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(b)); // never negotiated by Accept
        }
    }
}
