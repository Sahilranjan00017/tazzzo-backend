package com.tazzzo.media.admin;

import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.media.InvalidMediaException;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaIngestVerifier;
import com.tazzzo.media.MediaLookup;
import com.tazzzo.media.MediaNotFoundException;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.MediaSet;
import com.tazzzo.media.MediaStorage;
import com.tazzzo.media.MediaUploadPolicy;
import com.tazzzo.media.UploadTarget;
import com.tazzzo.media.UpsertMediaSetCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * INTERNAL admin transport for product/SKU media. Two steps, never proxying bytes: (1) {@code POST …/uploads} returns a
 * short-lived direct-to-storage target for ONE server-generated key; (2) {@code PUT …/{ownerType}/{ownerId}} replaces the
 * owner's WHOLE ordered set (CAS; the set-level invariants -- one PRIMARY at order 0, unique ids/orders -- live in the
 * domain) and, when storage is configured, verifies every NEWLY referenced key against what storage really holds (exists,
 * within the size ceiling, magic bytes match the declared type). The owner must be an existing product; the audit actor is
 * the authenticated principal.
 */
@RestController
@RequestMapping("/api/v1/admin/media")
public class MediaAdminController {

    static final String SOURCE = "admin-api";

    record UploadRequest(String ownerType, String ownerId, String contentType, Long sizeBytes) { }

    record UploadResponse(String assetKey, String method, String url, Map<String, String> headers, String expiresAt,
                          long maxBytes) { }

    record AssetDto(String assetId, String assetKey, String role, Integer sortOrder, String altText, Integer width,
                    Integer height, String contentType) { }

    record SetRequest(List<AssetDto> assets, Long expectedVersion) { }

    record SetResponse(String ownerType, String ownerId, long version, boolean active, List<AssetDto> assets) { }

    private final MediaService media;
    private final MediaStorage storage;
    private final MediaUploadPolicy policy;
    private final MediaIngestVerifier verifier;
    private final ProductQueryService products;

    public MediaAdminController(MediaService media, MediaStorage storage, MediaUploadPolicy policy,
                                MediaIngestVerifier verifier, ProductQueryService products) {
        this.media = media;
        this.storage = storage;
        this.policy = policy;
        this.verifier = verifier;
        this.products = products;
    }

    @PostMapping("/uploads")
    public ResponseEntity<UploadResponse> upload(@RequestBody UploadRequest body) {
        if (body == null || body.ownerId() == null || body.sizeBytes() == null) {
            throw new InvalidMediaException("ownerType, ownerId, contentType and sizeBytes are required");
        }
        MediaOwnerType type = owner(body.ownerType());
        policy.requireAcceptable(body.contentType(), body.sizeBytes());
        products.requireProduct(body.ownerId());
        if (!storage.enabled()) {
            throw new MediaStorageUnavailableException();
        }
        String key = policy.newKey(type, body.ownerId(), body.contentType());
        UploadTarget target = storage.createUpload(key, body.contentType(), policy.maxBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(key, target.method(), target.url(),
                target.headers(), target.expiresAt().toString(), policy.maxBytes()));
    }

    @GetMapping("/{ownerType}/{ownerId}")
    public SetResponse get(@PathVariable("ownerType") String ownerType, @PathVariable("ownerId") String ownerId) {
        MediaOwnerType type = owner(ownerType);
        products.requireProduct(ownerId);
        MediaLookup lookup = media.findMedia(type, ownerId);
        MediaSet set = lookup.mediaSet();
        if (set == null) {
            throw new MediaNotFoundException("no media set for this owner");
        }
        return view(set);
    }

    @PutMapping("/{ownerType}/{ownerId}")
    public ResponseEntity<SetResponse> put(@PathVariable("ownerType") String ownerType, @PathVariable("ownerId") String ownerId,
                                           @RequestBody SetRequest body, HttpServletRequest request) {
        MediaOwnerType type = owner(ownerType);
        if (body == null || body.assets() == null) {
            throw new InvalidMediaException("assets is required (an empty list clears the set)");
        }
        products.requireProduct(ownerId);
        List<MediaAsset> assets = body.assets().stream().map(MediaAdminController::asset).toList();
        if (verifier.verifying()) {
            Set<String> existing = new HashSet<>();
            MediaSet current = media.findMedia(type, ownerId).mediaSet();
            if (current != null) {
                current.assets().forEach(a -> existing.add(a.assetKey()));
            }
            for (MediaAsset a : assets) {
                if (!existing.contains(a.assetKey())) {
                    verifier.verify(a.assetKey(), a.contentType());
                }
            }
        }
        media.upsertMediaSet(new UpsertMediaSetCommand(type, ownerId, assets, SOURCE, body.expectedVersion()),
                AdminActors.require(request));
        return ResponseEntity.status(body.expectedVersion() == null ? HttpStatus.CREATED : HttpStatus.OK)
                .body(get(ownerType, ownerId));
    }

    private static MediaOwnerType owner(String raw) {
        try {
            return MediaOwnerType.valueOf(raw == null ? "" : raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidMediaException("ownerType must be product or sku");
        }
    }

    private static MediaAsset asset(AssetDto a) {
        if (a == null || a.role() == null || a.sortOrder() == null) {
            throw new InvalidMediaException("every asset needs assetId, assetKey, role and sortOrder");
        }
        try {
            return new MediaAsset(a.assetId(), a.assetKey(), ImageRole.valueOf(a.role()), a.sortOrder(), a.altText(),
                    a.width(), a.height(), a.contentType());
        } catch (IllegalArgumentException e) {
            throw new InvalidMediaException(e.getMessage());
        }
    }

    private static SetResponse view(MediaSet s) {
        return new SetResponse(s.ownerType().name().toLowerCase(Locale.ROOT), s.ownerId(), s.version(), s.active(),
                s.assets().stream().map(a -> new AssetDto(a.assetId(), a.assetKey(), a.role().name(), a.sortOrder(), a.altText(),
                        a.width(), a.height(), a.contentType())).toList());
    }

    /** Uploads were requested but no object storage is configured. */
    static final class MediaStorageUnavailableException extends RuntimeException {
        MediaStorageUnavailableException() {
            super("media storage is not configured");
        }
    }
}
