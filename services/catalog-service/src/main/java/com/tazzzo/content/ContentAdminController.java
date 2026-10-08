package com.tazzzo.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
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
    record PayloadDto(String imageAssetKey, String link, List<String> ids, String faqCategory, String question, String answer) { }

    /** {@code audience}: APP_ONLY | WEB_ONLY | BOTH; absent on create = BOTH, absent on update = unchanged. */
    record BlockRequest(String placement, String type, String title, Integer sort, String startsAt, String endsAt, PayloadDto payload,
                        Long expectedVersion, String audience) { }

    record StatusRequest(String to, Long expectedVersion) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BlockResponse(String blockId, String placement, String type, String title, int sort, String status, String startsAt, String endsAt,
                         PayloadDto payload, String audience, long version, String createdAt, String updatedAt) {
        static BlockResponse of(ContentBlock b) {
            return new BlockResponse(b.blockId(), b.placement().name(), b.type().name(), b.title(), b.sort(), b.status().name(),
                    b.startsAt() == null ? null : b.startsAt().toString(), b.endsAt() == null ? null : b.endsAt().toString(),
                    new PayloadDto(b.payload().imageAssetKey(), b.payload().link(), b.payload().ids().isEmpty() ? null : b.payload().ids(),
                            b.payload().faqCategory(), b.payload().question(), b.payload().answer()),
                    b.audience().name(), b.version(), b.createdAt().toString(), b.updatedAt().toString());
        }
    }

    record BlockList(List<BlockResponse> items) { }

    record ConfigRequest(Boolean storeOpen, Boolean maintenance, String maintenanceMessage, String minAndroid, String latestAndroid,
                         String minIos, String latestIos, String supportPhone, String supportEmail, String termsUrl, String privacyUrl,
                         String refundPolicyUrl, Long expectedVersion) { }

    private final ContentService content;

    public ContentAdminController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/api/v1/admin/content/blocks")
    public BlockList list(@RequestParam(name = "placement", defaultValue = "HOME") String placement,
                          @RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "audience", required = false) String audience) {
        return new BlockList(content.list(placement, status, audience).stream().map(BlockResponse::of).toList());
    }

    @GetMapping("/api/v1/admin/content/blocks/{id}")
    public BlockResponse get(@PathVariable String id) {
        return BlockResponse.of(content.get(id));
    }

    @PostMapping("/api/v1/admin/content/blocks")
    public ResponseEntity<BlockResponse> create(@RequestBody BlockRequest body, HttpServletRequest request) {
        if (body == null || body.sort() == null || body.payload() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "sort and payload are required");
        ContentBlock b = content.create(AdminActors.require(request), body.placement() == null ? "HOME" : body.placement(), body.type(),
                body.title(), body.sort(), instant(body.startsAt()), instant(body.endsAt()), payload(body.payload()), body.audience());
        return ResponseEntity.status(HttpStatus.CREATED).body(BlockResponse.of(b));
    }

    @PutMapping("/api/v1/admin/content/blocks/{id}")
    public BlockResponse update(@PathVariable String id, @RequestBody BlockRequest body, HttpServletRequest request) {
        if (body == null || body.sort() == null || body.payload() == null || body.expectedVersion() == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, "sort, payload and expectedVersion are required");
        }
        if (body.type() != null || body.placement() != null) throw new ContentFailure(ContentFailure.Reason.INVALID, "type and placement are fixed at creation");
        return BlockResponse.of(content.update(AdminActors.require(request), id, body.expectedVersion(), body.title(), body.sort(),
                instant(body.startsAt()), instant(body.endsAt()), payload(body.payload()), body.audience()));
    }

    @PostMapping("/api/v1/admin/content/blocks/{id}/status")
    public BlockResponse status(@PathVariable String id, @RequestBody StatusRequest body, HttpServletRequest request) {
        if (body == null || body.expectedVersion() == null) throw new ContentFailure(ContentFailure.Reason.INVALID, "expectedVersion is required");
        return BlockResponse.of(content.setStatus(AdminActors.require(request), id, body.expectedVersion(), body.to()));
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
        return new ContentBlock.Payload(p.imageAssetKey(), p.link(), p.ids(), p.faqCategory(), p.question(), p.answer());
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
            };
            String code = switch (e.reason()) {
                case INVALID -> "INVALID_CONTENT";
                case NOT_FOUND -> "NOT_FOUND";
                case STALE_VERSION -> "STALE_VERSION";
                case STATE_CONFLICT -> "STATE_CONFLICT";
            };
            Map<String, String> b = new LinkedHashMap<>();
            b.put("code", code);
            b.put("message", e.getMessage());
            b.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
            return ResponseEntity.status(s).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(b)); // never negotiated by Accept
        }
    }
}
